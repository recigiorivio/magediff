package br.com.rivio.magediff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Comparação de duas pastas: quais arquivos existem só de um lado e quais
 * existem nos dois com conteúdo diferente.
 *
 * <p>A correspondência é pelo caminho RELATIVO à raiz de cada lado — é o que faz
 * "a mesma configuração em dois ambientes" ou "a versão do cliente contra o
 * template" aparecerem como pares, mesmo com as raízes em lugares diferentes do
 * disco.
 *
 * <p>Diferente do modo Git, aqui os DOIS lados são arquivos reais: gravar e
 * copiar blocos funcionam nas duas direções.
 */
public final class FolderPair {

  /**
   * Pastas que nunca entram na comparação. Não é preferência: comparar
   * {@code .git} ou {@code node_modules} produz milhares de diferenças que
   * ninguém quer ver e que escondem as que importam.
   */
  private static final Set<String> IGNORED = Set.of(
      ".git", "node_modules", "target", "build", "dist", ".idea", ".gradle", "__pycache__");

  /** Acima disso a comparação byte a byte deixa de ser instantânea; o tamanho
   * diferente já resolve a maioria dos casos antes de chegar aqui. */
  private static final long BIG_FILE = 20L * 1024 * 1024;

  private final Path left;
  private final Path right;

  public FolderPair(Path left, Path right) {
    this.left = left;
    this.right = right;
  }

  public Path left() {
    return left;
  }

  public Path right() {
    return right;
  }

  /**
   * Arquivos que diferem. Os iguais ficam de fora — a lista é de trabalho a
   * fazer, e uma pasta com 900 arquivos idênticos e 3 diferentes não deve pedir
   * que você ache os 3.
   */
  public List<ChangedFile> changes() throws IOException {
    Set<String> onLeft = relativeFiles(left);
    Set<String> onRight = relativeFiles(right);

    List<ChangedFile> out = new ArrayList<>();
    Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    all.addAll(onLeft);
    all.addAll(onRight);

    for (String relative : all) {
      boolean hasLeft = onLeft.contains(relative);
      boolean hasRight = onRight.contains(relative);
      if (hasLeft && !hasRight) {
        out.add(ChangedFile.removed(relative));
      } else if (!hasLeft && hasRight) {
        out.add(ChangedFile.added(relative));
      } else if (differs(left.resolve(relative), right.resolve(relative))) {
        out.add(ChangedFile.modified(relative));
      }
    }
    return out;
  }

  /** O arquivo de um lado, ou {@code null} quando não existe ali. */
  public TextFile file(boolean isLeft, String relative) throws IOException {
    Path path = (isLeft ? left : right).resolve(relative);
    return Files.isRegularFile(path) ? TextFile.read(path) : null;
  }

  private static Set<String> relativeFiles(Path root) throws IOException {
    Set<String> out = new TreeSet<>();
    if (!Files.isDirectory(root)) {
      return out;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .filter(path -> !isIgnored(root, path))
          .forEach(path -> out.add(root.relativize(path).toString()));
    }
    return out;
  }

  /** Ignora por SEGMENTO do caminho, não por prefixo: `node_modules` aninhado
   * três níveis abaixo também tem que ficar de fora. */
  private static boolean isIgnored(Path root, Path path) {
    for (Path part : root.relativize(path)) {
      String name = part.toString();
      if (IGNORED.contains(name) || name.startsWith(".DS_Store")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Conteúdo diferente? Compara tamanho primeiro — é o descarte barato que
   * resolve a maioria dos casos sem ler byte nenhum.
   */
  private static boolean differs(Path a, Path b) {
    try {
      long sizeA = Files.size(a);
      if (sizeA != Files.size(b)) {
        return true;
      }
      if (sizeA > BIG_FILE) {
        // Mesmo tamanho e grande demais para conferir agora: trata como
        // diferente em vez de afirmar que é igual sem ter olhado.
        return true;
      }
      return Files.mismatch(a, b) != -1;
    } catch (IOException e) {
      // Ilegível de um dos lados é uma diferença relevante — esconder seria pior.
      return true;
    }
  }
}
