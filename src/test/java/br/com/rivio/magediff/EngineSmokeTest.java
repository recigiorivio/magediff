package br.com.rivio.magediff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import br.com.rivio.magediff.DiffEngine.Hunk;
import br.com.rivio.magediff.DiffEngine.Kind;
import br.com.rivio.magediff.DiffEngine.Result;
import br.com.rivio.magediff.DiffEngine.Row;

/**
 * Testes sem framework (roda com {@code java}, sem baixar JUnit).
 *
 * <p>O que interessa aqui não é o desenho — é a INVARIANTE do merge: aplicar
 * todos os blocos numa direção tem que deixar os dois arquivos idênticos, e tem
 * que terminar. É o que pega deriva de índice, que é o jeito de um comparador
 * corromper arquivo silenciosamente.
 */
public final class EngineSmokeTest {

  private static int failures;

  public static void main(String[] args) {
    numbering();
    pureInsertAndDelete();
    wordHighlight();
    collapse();
    mergeConverges();
    identicalFiles();
    emptyFiles();
    roundTripPreservesBytes();
    undoRedo();
    selectionToLineRange();
    ignoreWhitespace();
    contiguousBlocks();
    mergedRegionStaysVisible();
    gitMode();

    if (failures > 0) {
      System.out.printf("%n%d verificação(ões) falharam%n", failures);
      System.exit(1);
    }
    System.out.println("\ntodas as verificações passaram");
  }

  // ─── Casos ──────────────────────────────────────────────────────────────

  private static void numbering() {
    List<String> a = lines("um", "dois", "tres", "quatro");
    List<String> b = lines("um", "DOIS", "tres", "quatro");
    Result r = DiffEngine.diff(a, b, false);
    check("numeração: 1 alterada", r.added() == 1 && r.removed() == 1);
    check("numeração: 4 rows", r.rows().size() == 4);
    Row changed = r.rows().get(1);
    check("numeração: row 2 é REPLACE", changed.kind() == Kind.REPLACE);
    check("numeração: linhas 2/2", changed.oldLine() == 2 && changed.newLine() == 2);
    check("numeração: última row é 4/4",
        r.rows().get(3).oldLine() == 4 && r.rows().get(3).newLine() == 4);
    check("numeração: 1 hunk", r.hunks().size() == 1);
    Hunk h = r.hunks().get(0);
    check("numeração: hunk [1,2)×[1,2)",
        h.oldFrom() == 1 && h.oldTo() == 2 && h.newFrom() == 1 && h.newTo() == 2);
  }

  private static void pureInsertAndDelete() {
    Result ins = DiffEngine.diff(lines("a", "b"), lines("a", "novo", "b"), false);
    check("inserção pura: +1 -0", ins.added() == 1 && ins.removed() == 0);
    Hunk hi = ins.hunks().get(0);
    check("inserção pura: intervalo de origem vazio em 1",
        hi.oldFrom() == 1 && hi.oldTo() == 1 && hi.newFrom() == 1 && hi.newTo() == 2);

    Result del = DiffEngine.diff(lines("a", "velho", "b"), lines("a", "b"), false);
    check("remoção pura: +0 -1", del.added() == 0 && del.removed() == 1);
    Hunk hd = del.hunks().get(0);
    check("remoção pura: intervalo de destino vazio em 1",
        hd.oldFrom() == 1 && hd.oldTo() == 2 && hd.newFrom() == 1 && hd.newTo() == 1);

    // Inserção no começo do arquivo: âncora tem que ser 0, não -1.
    Result head = DiffEngine.diff(lines("a"), lines("zero", "a"), false);
    check("inserção no topo: âncora 0", head.hunks().get(0).oldFrom() == 0);
  }

  private static void wordHighlight() {
    Result r = DiffEngine.diff(
        lines("<select id=\"listar\" tipo=\"map\">"),
        lines("<select id=\"buscar\" tipo=\"map\">"), false);
    Row row = r.rows().get(0);
    String marked = mark(row.newSegments());
    check("realce: só o valor do id muda — " + marked,
        marked.equals("<select id=\"«buscar»\" tipo=\"map\">"));
    String markedOld = mark(row.oldSegments());
    check("realce: lado antigo marca listar — " + markedOld,
        markedOld.equals("<select id=\"«listar»\" tipo=\"map\">"));
  }

  private static void collapse() {
    List<String> a = new ArrayList<>();
    List<String> b = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      a.add("linha " + i);
      b.add("linha " + i);
    }
    b.set(20, "linha VINTE");
    Result collapsed = DiffEngine.diff(a, b, true);
    long gaps = collapsed.rows().stream().filter(row -> row.kind() == Kind.GAP).count();
    check("colapso: 2 separadores (antes e depois)", gaps == 2);
    check("colapso: 9 rows (3+gap+1+gap+3)", collapsed.rows().size() == 9);
    // Invariante em vez de número mágico: toda linha da esquerda está OU visível
    // numa row OU contada num separador. Se o colapso comer uma linha, isso
    // acusa; um "== 34" cravado só acusaria se eu tivesse feito a conta certa.
    int hidden = collapsed.rows().stream()
        .filter(row -> row.kind() == Kind.GAP)
        .mapToInt(Row::hidden)
        .sum();
    long shownOld = collapsed.rows().stream()
        .filter(row -> row.kind() != Kind.GAP && row.hasOld())
        .count();
    check("colapso: nada se perde — " + hidden + " escondidas + " + shownOld
        + " visíveis = " + a.size(), hidden + shownOld == a.size());

    Result full = DiffEngine.diff(a, b, false);
    check("sem colapso: 40 rows", full.rows().size() == 40);
  }

  /**
   * A invariante central: aplicar bloco por bloco, sempre recalculando o diff,
   * converge para arquivos idênticos — nas duas direções.
   */
  private static void mergeConverges() {
    List<List<String>> pairs = List.of(
        lines("a", "b", "c"), lines("a", "B", "c"),
        lines("a", "b", "c"), lines("x", "y", "z"),
        lines("a", "b", "c", "d", "e"), lines("a", "c", "e"),
        lines("a", "e"), lines("a", "b", "c", "d", "e"),
        lines(), lines("um", "dois"),
        lines("um", "dois"), lines(),
        lines("1", "2", "3", "4", "5", "6"), lines("1", "X", "3", "4", "Y", "6", "7"));

    for (int i = 0; i < pairs.size(); i += 2) {
      converge(pairs.get(i), pairs.get(i + 1), true, "par " + (i / 2));
      converge(pairs.get(i), pairs.get(i + 1), false, "par " + (i / 2));
    }
  }

  private static void converge(List<String> leftSrc, List<String> rightSrc, boolean toRight,
      String label) {
    List<String> leftLines = new ArrayList<>(leftSrc);
    List<String> rightLines = new ArrayList<>(rightSrc);
    int guard = 0;
    Result r = DiffEngine.diff(leftLines, rightLines, false);
    while (!r.hunks().isEmpty()) {
      if (++guard > 200) {
        check(label + " (" + dir(toRight) + "): converge", false);
        return;
      }
      // A MESMA chamada que a seta da UI faz — não uma reimplementação.
      Hunk h = r.hunks().get(0);
      if (toRight) {
        Merge.toRight(h, leftLines, rightLines);
      } else {
        Merge.toLeft(h, leftLines, rightLines);
      }
      r = DiffEngine.diff(leftLines, rightLines, false);
    }
    boolean equal = leftLines.equals(rightLines);
    boolean keptTarget = toRight ? leftLines.equals(leftSrc) : rightLines.equals(rightSrc);
    check(label + " (" + dir(toRight) + "): converge para idêntico", equal);
    check(label + " (" + dir(toRight) + "): o lado de origem não foi tocado", keptTarget);
  }

  private static void identicalFiles() {
    List<String> a = lines("igual", "igual 2");
    Result r = DiffEngine.diff(a, new ArrayList<>(a), true);
    check("idênticos: nenhum hunk", r.identical());
    check("idênticos: +0 -0", r.added() == 0 && r.removed() == 0);
    check("idênticos: arquivo inteiro visível (não colapsa sem mudança)",
        r.rows().size() == 2);
  }

  private static void emptyFiles() {
    Result r = DiffEngine.diff(lines(), lines(), false);
    check("vazios: nenhum hunk e nenhuma row", r.identical() && r.rows().isEmpty());
  }

  /**
   * Ler e gravar sem alterar nada tem que devolver os MESMOS bytes (a exceção
   * documentada é EOL misto). Sem isso, um
   * merge de uma linha num arquivo CRLF com BOM reescreve o arquivo inteiro e o
   * diff do git mostra 400 linhas mudadas — dano maior que o do merge.
   */
  private static void roundTripPreservesBytes() {
    // {nome, entrada, saída esperada}. Só o caso de EOL misto sai diferente da
    // entrada: ali a gravação normaliza para o EOL majoritário — deliberado (EOL
    // misto já é defeito) e sinalizado na barra de status, não escondido.
    String[][] cases = {
        {"crlf+bom", "\uFEFFum\r\ndois\r\n", "\uFEFFum\r\ndois\r\n"},
        {"lf simples", "um\ndois\n", "um\ndois\n"},
        {"sem quebra final", "um\ndois", "um\ndois"},
        {"crlf sem quebra final", "um\r\ndois", "um\r\ndois"},
        {"vazio", "", ""},
        {"só quebra", "\n", "\n"},
        {"eol misto → normaliza p/ o majoritário", "a\r\nb\r\nc\n", "a\r\nb\r\nc\r\n"},
    };
    for (String[] c : cases) {
      try {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("filediff", ".txt");
        java.nio.file.Files.write(tmp,
            c[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TextFile file = TextFile.read(tmp);
        file.write(tmp);
        byte[] after = java.nio.file.Files.readAllBytes(tmp);
        java.nio.file.Files.delete(tmp);
        byte[] expected = c[2].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean same = Arrays.equals(expected, after);
        check("round-trip " + c[0] + (same ? ""
            : " (esperado " + expected.length + " bytes, saiu " + after.length + ")"), same);
      } catch (java.io.IOException e) {
        check("round-trip " + c[0] + ": " + e.getMessage(), false);
      }
    }

    check("eol misto é detectado", detectMixed("a\r\nb\n"));
    check("crlf puro não é misto", !detectMixed("a\r\nb\r\n"));
    check("lf puro não é misto", !detectMixed("a\nb\n"));
  }

  private static boolean detectMixed(String content) {
    try {
      java.nio.file.Path tmp = java.nio.file.Files.createTempFile("filediff", ".txt");
      java.nio.file.Files.write(tmp, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      boolean mixed = TextFile.read(tmp).mixedEol();
      java.nio.file.Files.delete(tmp);
      return mixed;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** Desfazer tem que devolver o conteúdo E o estado de "não gravado". */
  private static void undoRedo() {
    TextFile left = fileWith("a", "b", "c");
    TextFile right = fileWith("a", "B", "c");
    UndoStack history = new UndoStack();

    check("undo: começa sem nada a desfazer", !history.canUndo() && !history.canRedo());

    history.record("copiar para a direita", left, right);
    Merge.toRight(DiffEngine.diff(left.lines(), right.lines(), false).hunks().get(0),
        left.lines(), right.lines());
    right.markDirty();
    check("undo: merge aplicado", right.lines().equals(List.of("a", "b", "c")));
    check("undo: direita marcada como alterada", right.dirty());

    check("undo: desfaz", history.undo(left, right));
    check("undo: conteúdo volta", right.lines().equals(List.of("a", "B", "c")));
    check("undo: o 'não gravado' volta junto", !right.dirty());
    check("undo: esquerda intocada", left.lines().equals(List.of("a", "b", "c")));

    check("redo: disponível depois de desfazer", history.canRedo());
    check("redo: refaz", history.redo(left, right));
    check("redo: conteúdo reaplicado", right.lines().equals(List.of("a", "b", "c")));
    check("redo: 'não gravado' reaplicado", right.dirty());

    // Um caminho novo tem que apagar o futuro.
    history.undo(left, right);
    history.record("colar", left, right);
    check("redo é descartado após uma alteração nova", !history.canRedo());

    // A restauração precisa usar A MESMA lista: a tela e o TextFile a seguram.
    List<String> identity = right.lines();
    history.undo(left, right);
    check("undo restaura sem trocar a referência da lista", right.lines() == identity);
  }

  /**
   * Seleção (em linhas da tela) → intervalo de linhas do arquivo. É o que
   * copiar/colar usam; errar aqui cola no lugar errado.
   */
  private static void selectionToLineRange() {
    List<String> left = lines("a", "b", "c", "d");
    List<String> right = lines("a", "b", "NOVA", "c", "d");
    DiffView view = new DiffView();
    Result r = DiffEngine.diff(left, right, false);
    view.setData(r, left, right);

    // rows: 0=a/a 1=b/b 2=(vazio)/NOVA 3=c/c 4=d/d
    view.selectRows(DiffView.Side.RIGHT, 2, 2);
    int[] onlyNew = view.selectionLineRange(DiffView.Side.RIGHT);
    check("seleção da linha inserida (direita) → [2,3)",
        onlyNew[0] == 2 && onlyNew[1] == 3);

    int[] sameOnLeft = view.selectionLineRange(DiffView.Side.LEFT);
    check("a mesma seleção na esquerda → intervalo VAZIO em 2 (colar insere ali)",
        sameOnLeft[0] == 2 && sameOnLeft[1] == 2);

    view.selectRows(DiffView.Side.LEFT, 0, 3);
    int[] span = view.selectionLineRange(DiffView.Side.LEFT);
    check("seleção de 4 rows na esquerda → [0,3)", span[0] == 0 && span[1] == 3);

    view.clearSelection();
    check("sem seleção não há intervalo", !view.hasSelection());
  }

  /**
   * "Ignorar espaços" muda o que CONTA como diferença, nunca o conteúdo. Se um
   * dia essa opção passar a normalizar as linhas de verdade, o merge começa a
   * gravar indentação alheia no arquivo do usuário — daí a última checagem.
   */
  private static void ignoreWhitespace() {
    List<String> a = lines("class X {", "    int a = 1;", "  }");
    List<String> b = lines("class X {", "\tint a = 1;", "}");

    Result strict = DiffEngine.diff(a, b, false, false);
    check("sem ignorar: indentação diferente é diferença", !strict.identical());

    Result loose = DiffEngine.diff(a, b, false, true);
    check("ignorando espaços: nenhum bloco a copiar", loose.identical());
    check("ignorando espaços: 2 linhas marcadas como 'só de espaço'", loose.minor() == 2);
    check("ignorando espaços: +0 -0", loose.added() == 0 && loose.removed() == 0);

    Row minorRow = loose.rows().get(1);
    check("a linha marcada é MINOR", minorRow.kind() == Kind.MINOR);
    check("o texto exibido é o ORIGINAL de cada lado, não o normalizado",
        mark(minorRow.oldSegments()).equals("    int a = 1;")
            && mark(minorRow.newSegments()).equals("\tint a = 1;"));

    // Diferença real continua aparecendo mesmo ignorando espaço.
    List<String> c = lines("class X {", "    int a = 2;", "  }");
    Result mixed = DiffEngine.diff(a, c, false, true);
    check("ignorando espaços: mudança de verdade continua sendo bloco",
        mixed.hunks().size() == 1);

    check("as listas de entrada não foram tocadas",
        a.equals(List.of("class X {", "    int a = 1;", "  }")));
  }

  /**
   * Exemplo canônico do patience diff: uma função nova entra antes de outra, num
   * arquivo cheio de linhas estruturais repetidas ({@code \{}, {@code \}},
   * {@code return 1;}). Myers e Histogram têm o mesmo custo mínimo aqui, mas
   * escolhem soluções diferentes: medido, o Myers do JGit parte a remoção em
   * DOIS blocos adjacentes e o Histogram devolve um só.
   *
   * <p>Dois blocos adjacentes não são detalhe acadêmico: viram duas setas na
   * tela e dois cliques para resolver o que é uma mudança só.
   *
   * <p>O teste checa a PROPRIEDADE (nenhum par de blocos encostado), não os
   * índices — assim ele continua valendo se o JGit mudar de versão, e quebra se
   * alguém trocar o algoritmo de volta.
   */
  private static void contiguousBlocks() {
    List<String> a = lines(
        "#include <stdio.h>", "", "// Frobs foo heartily", "int frobnitz(int foo)", "{",
        "    int i;", "    for(i = 0; i < 10; i++)", "    {", "        printf(\"resposta: \");",
        "        printf(\"%d\\n\", foo);", "    }", "}", "", "int fact(int n)", "{",
        "    if(n > 1)", "    {", "        return fact(n-1) * n;", "    }", "    return 1;",
        "}", "", "int main(int argc, char **argv)", "{", "    frobnitz(fact(10));", "}");
    List<String> b = lines(
        "#include <stdio.h>", "", "int fib(int n)", "{", "    if(n > 2)", "    {",
        "        return fib(n-1) + fib(n-2);", "    }", "    return 1;", "}", "",
        "// Frobs foo heartily", "int frobnitz(int foo)", "{", "    int i;",
        "    for(i = 0; i < 10; i++)", "    {", "        printf(\"%d\\n\", foo);", "    }",
        "}", "", "int main(int argc, char **argv)", "{", "    frobnitz(fib(10));", "}");

    Result r = DiffEngine.diff(a, b, false);
    List<Hunk> hunks = r.hunks();
    boolean touching = false;
    for (int i = 1; i < hunks.size(); i++) {
      Hunk prev = hunks.get(i - 1);
      Hunk cur = hunks.get(i);
      if (prev.oldTo() == cur.oldFrom() && prev.newTo() == cur.newFrom()) {
        touching = true;
      }
    }
    check("blocos adjacentes seriam duas setas para uma mudança só — não há nenhum ("
        + hunks.size() + " blocos)", !touching);
  }

  /**
   * Depois de copiar um bloco, os dois lados ficam iguais ali — e com "só
   * mudanças" ligado o trecho seria engolido pelo colapso, levando junto o ↶ de
   * desfazer e a noção de ONDE a mudança foi aplicada. O intervalo protegido
   * mantém o rastro visível.
   */
  private static void mergedRegionStaysVisible() {
    List<String> a = new ArrayList<>();
    List<String> b = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      a.add("linha " + i);
      b.add("linha " + i);
    }
    // DUAS mudanças distantes. Com uma só, depois da cópia os arquivos ficariam
    // idênticos e o colapso nem roda (arquivo igual é mostrado inteiro) — o
    // problema só existe quando SOBRA diferença mantendo o colapso ativo.
    b.set(10, "linha DEZ alterada");
    b.set(50, "linha CINQUENTA alterada");

    Result before = DiffEngine.diff(a, b, true);
    check("cenário: 2 blocos distantes", before.hunks().size() == 2);
    Hunk first = before.hunks().get(0);
    Merge.toRight(first, a, b);

    // Sem proteger: o trecho mesclado cai no meio de um bloco de linhas iguais
    // e desaparece dentro do separador.
    Result unprotected = DiffEngine.diff(a, b, true);
    check("sem proteção, o trecho mesclado é engolido pelo colapso",
        !hasRowForOldLine(unprotected, 10));

    // Protegendo o rastro: continua visível, e nada de linha perdida.
    Result guarded = DiffEngine.diff(a, b, true, false, first.oldFrom(), first.oldTo());
    check("com proteção, o trecho mesclado continua visível",
        hasRowForOldLine(guarded, 10));
    check("proteger não inventa nem perde linha", totalOldLines(guarded) == a.size());
    check("a outra diferença continua sendo bloco", guarded.hunks().size() == 1);
  }

  /** Se existe uma row (não-separador) exibindo aquela linha da esquerda. */
  private static boolean hasRowForOldLine(Result result, int zeroBasedLine) {
    return result.rows().stream()
        .anyMatch(row -> row.kind() != Kind.GAP && row.oldLine() == zeroBasedLine + 1);
  }

  private static int totalOldLines(Result result) {
    int hidden = result.rows().stream()
        .filter(row -> row.kind() == Kind.GAP)
        .mapToInt(Row::hidden)
        .sum();
    long shown = result.rows().stream()
        .filter(row -> row.kind() != Kind.GAP && row.hasOld())
        .count();
    return hidden + (int) shown;
  }

  /**
   * Modo Git, contra um repositório DE VERDADE criado num diretório temporário.
   * Um teste com repositório falso ("e se o JGit devolvesse isto…") provaria
   * apenas que eu sei escrever mocks; o que precisa valer é o comportamento
   * contra as árvores e blobs reais.
   */
  private static void gitMode() {
    java.nio.file.Path dir = null;
    try {
      dir = java.nio.file.Files.createTempDirectory("magediff-git");
      try (org.eclipse.jgit.api.Git git =
          org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile()).call()) {

        write(dir, "igual.txt", "sem mudança\n");
        write(dir, "mudou.txt", "linha um\nlinha dois\n");
        write(dir, "sumiu.txt", "vou ser apagado\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("primeiro").setAuthor("t", "t@t").call();

        // Estado local: um arquivo alterado, um apagado, um novo.
        write(dir, "mudou.txt", "linha um\nlinha DOIS\n");
        java.nio.file.Files.delete(dir.resolve("sumiu.txt"));
        write(dir, "novo.txt", "acabei de nascer\n");

        try (GitRepo repo = GitRepo.open(dir)) {
          check("git: abriu o repositório", repo != null);
          GitRepo.Side work = GitRepo.Side.workingTree();
          GitRepo.Side head = GitRepo.Side.revision("HEAD", "HEAD");

          List<GitRepo.ChangedFile> changes = repo.changes(head, work);
          check("git: 3 arquivos diferem do HEAD (achou " + changes.size() + ")",
              changes.size() == 3);
          check("git: o inalterado NÃO está na lista",
              changes.stream().noneMatch(c -> c.path().equals("igual.txt")));
          check("git: classifica adicionado/removido/alterado",
              kindOf(changes, "novo.txt") == GitRepo.ChangeKind.ADICIONADO
                  && kindOf(changes, "sumiu.txt") == GitRepo.ChangeKind.REMOVIDO
                  && kindOf(changes, "mudou.txt") == GitRepo.ChangeKind.ALTERADO);

          // O conteúdo de cada lado é o que alimenta o diff.
          TextFile antes = repo.file(head, "mudou.txt");
          TextFile agora = repo.file(work, "mudou.txt");
          check("git: lê o conteúdo do commit",
              antes != null && antes.lines().equals(List.of("linha um", "linha dois")));
          check("git: lê o conteúdo da pasta de trabalho",
              agora != null && agora.lines().equals(List.of("linha um", "linha DOIS")));
          check("git: o lado do commit é read-only (não há onde gravar)",
              antes.readOnly() && !agora.readOnly());

          Result diff = DiffEngine.diff(antes.lines(), agora.lines(), false);
          check("git: o diff do arquivo dá 1 bloco", diff.hunks().size() == 1);

          check("git: arquivo ausente de um lado devolve null",
              repo.file(work, "sumiu.txt") == null && repo.file(head, "novo.txt") == null);

          // Uma branch nova entra na lista de lados.
          git.branchCreate().setName("experimento").call();
          boolean hasBranch = repo.sides().stream()
              .anyMatch(side -> side.label().contains("experimento"));
          check("git: branches aparecem como lado comparável", hasBranch);
          check("git: a pasta de trabalho é o primeiro lado oferecido",
              repo.sides().get(0).isWorkingTree());
        }
      }
    } catch (Exception e) {
      check("git: " + e, false);
    } finally {
      deleteTree(dir);
    }
  }

  private static GitRepo.ChangeKind kindOf(List<GitRepo.ChangedFile> changes, String path) {
    return changes.stream().filter(c -> c.path().equals(path))
        .map(GitRepo.ChangedFile::kind).findFirst().orElse(null);
  }

  private static void write(java.nio.file.Path dir, String name, String content)
      throws java.io.IOException {
    java.nio.file.Files.writeString(dir.resolve(name), content);
  }

  private static void deleteTree(java.nio.file.Path dir) {
    if (dir == null) {
      return;
    }
    try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          java.nio.file.Files.deleteIfExists(p);
        } catch (java.io.IOException ignored) {
          // temporário: falha na limpeza não invalida o teste
        }
      });
    } catch (java.io.IOException ignored) {
      // idem
    }
  }

  private static TextFile fileWith(String... content) {
    try {
      java.nio.file.Path tmp = java.nio.file.Files.createTempFile("filediff", ".txt");
      java.nio.file.Files.write(tmp,
          String.join("\n", content).concat("\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      TextFile file = TextFile.read(tmp);
      java.nio.file.Files.delete(tmp);
      return file;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ─── Infra ──────────────────────────────────────────────────────────────

  private static String dir(boolean toRight) {
    return toRight ? "→" : "←";
  }

  private static List<String> lines(String... values) {
    return new ArrayList<>(Arrays.asList(values));
  }

  private static String mark(List<DiffEngine.Segment> segments) {
    StringBuilder sb = new StringBuilder();
    for (DiffEngine.Segment seg : segments) {
      sb.append(seg.changed() ? "«" + seg.text() + "»" : seg.text());
    }
    return sb.toString();
  }

  private static void check(String label, boolean ok) {
    System.out.printf("%s %s%n", ok ? "ok  " : "FALHA", label);
    if (!ok) {
      failures++;
    }
  }
}
