package br.com.rivio.magediff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.NullOutputStream;

/**
 * O modo Git: comparar o que está na pasta de trabalho com um commit, ou dois
 * commits/branches entre si.
 *
 * <p>Usa o JGit que já estava no projeto pelo algoritmo de diff — ele é uma
 * implementação completa do Git, então ler branches, árvores e blobs não exige
 * chamar o executável `git` nem depender de ele estar instalado e no PATH.
 *
 * <p>Só leitura. Gravar continua sendo trabalho do {@link TextFile}, e apenas do
 * lado da pasta de trabalho: um commit é imutável, então o lado "revisão" é
 * read-only por natureza, não por decisão de interface.
 */
public final class GitRepo implements AutoCloseable {

  /**
   * Um dos dois lados da comparação. {@code rev} nulo significa "a pasta de
   * trabalho como está agora" — o único lado que se pode gravar.
   */
  public record Side(String label, String rev) {

    public static Side workingTree() {
      return new Side("Pasta de trabalho", null);
    }

    public static Side revision(String label, String rev) {
      return new Side(label, rev);
    }

    public boolean isWorkingTree() {
      return rev == null;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  public enum ChangeKind {
    ADICIONADO, REMOVIDO, ALTERADO, RENOMEADO
  }

  /** Um arquivo que difere entre os dois lados. {@code oldPath} só difere de
   * {@code path} em renomeação. */
  public record ChangedFile(String path, String oldPath, ChangeKind kind) {

    @Override
    public String toString() {
      return path;
    }
  }

  private final Repository repository;

  private GitRepo(Repository repository) {
    this.repository = repository;
  }

  /**
   * Abre o repositório que CONTÉM o caminho informado — aceita a raiz ou
   * qualquer subpasta, como o próprio git faz. Devolve {@code null} quando não
   * há repositório acima do caminho.
   */
  public static GitRepo open(Path anywhereInside) throws IOException {
    Repository repo = new FileRepositoryBuilder()
        .findGitDir(anywhereInside.toFile())
        .readEnvironment()
        .build();
    if (repo.getDirectory() == null) {
      repo.close();
      return null;
    }
    return new GitRepo(repo);
  }

  public Path workTree() {
    return repository.getWorkTree().toPath();
  }

  public String currentBranch() throws IOException {
    String branch = repository.getBranch();
    return branch == null ? "(sem branch)" : branch;
  }

  /**
   * Lados oferecidos: a pasta de trabalho, o HEAD, e cada branch local e remota.
   * A ordem é a útil, não a alfabética — comparar com o HEAD é o caso comum e
   * fica no topo.
   */
  public List<Side> sides() throws Exception {
    List<Side> sides = new ArrayList<>();
    sides.add(Side.workingTree());
    if (repository.resolve(Constants.HEAD) != null) {
      sides.add(Side.revision("HEAD (último commit)", Constants.HEAD));
    }
    try (Git git = new Git(repository)) {
      for (Ref ref : git.branchList().call()) {
        String name = Repository.shortenRefName(ref.getName());
        sides.add(Side.revision("branch " + name, ref.getName()));
      }
      for (Ref ref : git.branchList()
          .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()) {
        sides.add(Side.revision("remota " + Repository.shortenRefName(ref.getName()),
            ref.getName()));
      }
    }
    return sides;
  }

  /**
   * Arquivos que diferem entre os dois lados.
   *
   * <p>Detecção de renomeação ligada: sem ela, mover um arquivo aparece como uma
   * remoção e uma adição sem relação, e o diff do conteúdo — que é o que
   * interessa — se perde.
   */
  public List<ChangedFile> changes(Side from, Side to) throws Exception {
    List<ChangedFile> out = new ArrayList<>();
    try (ObjectReader reader = repository.newObjectReader();
        DiffFormatter formatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
      formatter.setRepository(repository);
      formatter.setDetectRenames(true);
      List<DiffEntry> entries =
          formatter.scan(treeIterator(from, reader), treeIterator(to, reader));
      for (DiffEntry entry : entries) {
        out.add(toChangedFile(entry));
      }
    }
    out.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
    return out;
  }

  private static ChangedFile toChangedFile(DiffEntry entry) {
    return switch (entry.getChangeType()) {
      case ADD -> new ChangedFile(entry.getNewPath(), entry.getNewPath(), ChangeKind.ADICIONADO);
      case DELETE -> new ChangedFile(entry.getOldPath(), entry.getOldPath(), ChangeKind.REMOVIDO);
      case RENAME -> new ChangedFile(entry.getNewPath(), entry.getOldPath(), ChangeKind.RENOMEADO);
      default -> new ChangedFile(entry.getNewPath(), entry.getOldPath(), ChangeKind.ALTERADO);
    };
  }

  private AbstractTreeIterator treeIterator(Side side, ObjectReader reader) throws IOException {
    if (side.isWorkingTree()) {
      return new FileTreeIterator(repository);
    }
    ObjectId commit = repository.resolve(side.rev());
    if (commit == null) {
      return new EmptyTreeIterator();
    }
    try (RevWalk walk = new RevWalk(repository)) {
      CanonicalTreeParser parser = new CanonicalTreeParser();
      parser.reset(reader, walk.parseCommit(commit).getTree());
      return parser;
    }
  }

  /**
   * Conteúdo de um arquivo num dos lados, ou {@code null} quando ele não existe
   * ali (adicionado ou removido). O caller mostra o lado vazio — que é
   * exatamente o que o diff já sabe representar.
   */
  public TextFile file(Side side, String path) throws IOException {
    if (side.isWorkingTree()) {
      Path onDisk = workTree().resolve(path);
      return java.nio.file.Files.isRegularFile(onDisk) ? TextFile.read(onDisk) : null;
    }
    ObjectId commit = repository.resolve(side.rev());
    if (commit == null) {
      return null;
    }
    try (RevWalk walk = new RevWalk(repository);
        TreeWalk treeWalk = TreeWalk.forPath(repository, path,
            walk.parseCommit(commit).getTree())) {
      if (treeWalk == null) {
        return null;
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      repository.open(treeWalk.getObjectId(0)).copyTo(buffer);
      // Nome com a revisão junto: na tela, "config.xml" dos dois lados não diz
      // qual é qual, e é o tipo de confusão que faz gravar por cima do errado.
      return TextFile.ofBytes(buffer.toByteArray(), StandardCharsets.UTF_8,
          path + " @ " + side.label());
    }
  }

  @Override
  public void close() {
    repository.close();
  }
}
