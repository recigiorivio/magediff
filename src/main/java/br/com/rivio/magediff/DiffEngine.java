package br.com.rivio.magediff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;

/**
 * Diff por linha entre dois arquivos, com realce do que mudou <b>dentro</b> da
 * linha e agrupamento em "hunks" — o bloco que as setas copiam de um lado para o
 * outro.
 *
 * <p>O alinhamento vem do <b>HistogramDiff do Eclipse JGit</b> — o mesmo motor
 * que uma implementação real do Git usa. Não é "Myers mais rápido": Myers
 * minimiza o número de edições, o que em código casa a chave de fechamento de
 * uma função com a de outra e transforma "esta função foi adicionada" num
 * emaranhado. O Histogram ancora primeiro nas linhas RARAS do arquivo e alinha
 * o resto em volta delas — é por isso que o git ganhou {@code --histogram}
 * depois de anos só com Myers. Num comparador visual isso é a diferença entre
 * enxergar a mudança e caçar a mudança.
 *
 * <p>Os {@code Edit} que ele devolve são (beginA, endA, beginB, endB): já é o
 * intervalo semiaberto dos dois lados que o {@link Hunk} precisa, inclusive
 * para inserção e remoção puras.
 *
 * <p>O que continua aqui é o que a biblioteca não faz: transformar o
 * alinhamento em linhas de tela, o realce por token, o colapso de contexto e a
 * distinção entre diferença real e diferença só de espaço.
 */
public final class DiffEngine {

  /** Linhas inalteradas mantidas em volta de cada mudança quando colapsado. */
  private static final int CONTEXT_LINES = 3;

  /** Só vale colapsar quando sobra pelo menos uma linha escondida — com um bloco
   * de 2*CONTEXT+1 o separador ocuparia o espaço da linha que esconde. */
  private static final int MIN_COLLAPSIBLE = CONTEXT_LINES * 2 + 2;

  /** Acima disso o realce intra-linha não é tentado. */
  private static final int MAX_WORD_DIFF_CHARS = 2000;

  /** Compilado uma vez: `replaceAll` recompila o padrão a cada linha. */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  /**
   * Sem estado entre chamadas, então uma instância só serve a tudo. O Histogram
   * já delega para o Myers do próprio JGit quando uma linha se repete demais
   * (limite de cadeia), em vez de degradar em silêncio.
   */
  private static final DiffAlgorithm ALGORITHM = new HistogramDiff();

  private DiffEngine() {
  }

  public enum Kind {
    /** Igual nos dois lados. */
    CONTEXT,
    /**
     * Igual para efeito de comparação, mas o texto difere — só acontece com
     * "ignorar espaços" ligado. É a "diferença sem importância" do Beyond
     * Compare: aparece marcada de leve, não entra na contagem e não é
     * navegável, porque quem ligou a opção disse que não quer parar nela.
     */
    MINOR,
    /** Existe só na direita. */
    ADD,
    /** Existe só na esquerda. */
    REMOVE,
    /** Existe nos dois, com conteúdo diferente. */
    REPLACE,
    /** Separador de linhas iguais escondidas. */
    GAP
  }

  /** Pedaço de uma linha. {@code changed} pinta o realce intra-linha; o fundo da
   * linha inteira vem do {@link Kind} da row. */
  public record Segment(String text, boolean changed) {
  }

  /**
   * Uma linha visual. {@code oldLine}/{@code newLine} são 1-based para exibição
   * e valem {@code -1} quando o lado não existe. {@code hunkIndex} é {@code -1}
   * fora de mudança. {@code hidden} só é usado em {@link Kind#GAP}.
   */
  public record Row(Kind kind, int oldLine, int newLine, List<Segment> oldSegments,
      List<Segment> newSegments, int hunkIndex, int hidden) {

    public boolean hasOld() {
      return oldSegments != null;
    }

    public boolean hasNew() {
      return newSegments != null;
    }

    /** Linha que conta como diferença de verdade (a que as setas copiam). */
    public boolean isChange() {
      return kind == Kind.ADD || kind == Kind.REMOVE || kind == Kind.REPLACE;
    }
  }

  /**
   * Um bloco de mudança, em índices 0-based e intervalo semiaberto sobre as
   * LISTAS DE LINHAS (não sobre as rows). É o que as setas manipulam: copiar
   * para a direita = {@code right[newFrom,newTo)} recebe
   * {@code left[oldFrom,oldTo)}.
   *
   * <p>Inserção pura tem {@code oldFrom == oldTo} e remoção pura tem
   * {@code newFrom == newTo} — por isso o intervalo é semiaberto e não um
   * "primeira/última linha": só assim "copiar" também sabe apagar.
   */
  public record Hunk(int oldFrom, int oldTo, int newFrom, int newTo) {

    public int oldCount() {
      return oldTo - oldFrom;
    }

    public int newCount() {
      return newTo - newFrom;
    }
  }

  public record Result(List<Row> rows, List<Hunk> hunks, int added, int removed, int minor) {

    public boolean identical() {
      return hunks.isEmpty();
    }
  }

  public static Result diff(List<String> left, List<String> right, boolean collapse) {
    return diff(left, right, collapse, false);
  }

  /**
   * @param ignoreWhitespace compara desprezando espaço em branco. O diff roda
   *     sobre chaves normalizadas, mas tudo o que é exibido, copiado e gravado
   *     sai das linhas ORIGINAIS — a opção muda o que conta como diferença,
   *     nunca o conteúdo do arquivo.
   */
  public static Result diff(List<String> left, List<String> right, boolean collapse,
      boolean ignoreWhitespace) {
    return diff(left, right, collapse, ignoreWhitespace, -1, -1);
  }

  /**
   * @param keepFrom intervalo de linhas da ESQUERDA (0-based, semiaberto) que
   *     nunca é colapsado, mesmo virando contexto. É o rastro da última cópia:
   *     depois de mesclar, os dois lados ficam iguais e o trecho seria engolido
   *     pelo "só mudanças" — levando junto o ↶ de desfazer e qualquer noção de
   *     onde a mudança foi aplicada. Manter visível dá o ponto de volta.
   */
  public static Result diff(List<String> left, List<String> right, boolean collapse,
      boolean ignoreWhitespace, int keepFrom, int keepTo) {
    List<String> leftKeys = ignoreWhitespace ? keys(left) : left;
    List<String> rightKeys = ignoreWhitespace ? keys(right) : right;
    EditList edits = ALGORITHM.diff(LineSequence.COMPARATOR,
        new LineSequence(leftKeys), new LineSequence(rightKeys));
    return emit(left, right, edits, collapse, keepFrom, keepTo);
  }

  /** Colapsa toda corrida de espaço e apara as pontas: é o critério de "mesma
   * linha, indentação diferente". Não mexe em espaço dentro de string literal —
   * distinguir isso exigiria conhecer a linguagem do arquivo. */
  private static List<String> keys(List<String> lines) {
    List<String> out = new ArrayList<>(lines.size());
    for (String line : lines) {
      out.add(WHITESPACE_RUN.matcher(line).replaceAll(" ").trim());
    }
    return out;
  }

  // ─── Emissão das rows ───────────────────────────────────────────────────

  /**
   * O {@code EditList} do JGit traz SÓ as mudanças — o contexto entre elas não
   * vem na lista e é preenchido aqui, andando com dois cursores. É o oposto de
   * uma API que devolve também os trechos iguais: dá um pouco mais de trabalho
   * e, em troca, o alinhamento de cada bloco é explícito nos quatro índices.
   */
  private static Result emit(List<String> left, List<String> right, EditList edits,
      boolean collapse, int keepFrom, int keepTo) {
    List<Row> rows = new ArrayList<>();
    List<Hunk> hunks = new ArrayList<>();
    int added = 0;
    int removed = 0;
    int minor = 0;
    int oi = 0;
    int ni = 0;

    for (Edit edit : edits) {
      while (oi < edit.getBeginA()) {
        minor += context(rows, left, right, oi, ni);
        oi++;
        ni++;
      }

      int hunkIndex = hunks.size();
      hunks.add(new Hunk(edit.getBeginA(), edit.getEndA(), edit.getBeginB(), edit.getEndB()));
      removed += edit.getLengthA();
      added += edit.getLengthB();

      int paired = Math.min(edit.getLengthA(), edit.getLengthB());
      for (int k = 0; k < paired; k++) {
        Segments seg = wordDiff(left.get(edit.getBeginA() + k), right.get(edit.getBeginB() + k));
        rows.add(new Row(Kind.REPLACE, edit.getBeginA() + k + 1, edit.getBeginB() + k + 1,
            seg.old(), seg.neu(), hunkIndex, 0));
      }
      for (int k = paired; k < edit.getLengthA(); k++) {
        int index = edit.getBeginA() + k;
        rows.add(new Row(Kind.REMOVE, index + 1, -1, plain(left.get(index)), null, hunkIndex, 0));
      }
      for (int k = paired; k < edit.getLengthB(); k++) {
        int index = edit.getBeginB() + k;
        rows.add(new Row(Kind.ADD, -1, index + 1, null, plain(right.get(index)), hunkIndex, 0));
      }

      oi = edit.getEndA();
      ni = edit.getEndB();
    }

    while (oi < left.size()) {
      minor += context(rows, left, right, oi, ni);
      oi++;
      ni++;
    }

    List<Row> finalRows = collapse && !hunks.isEmpty()
        ? collapseContext(rows, keepFrom, keepTo) : rows;
    return new Result(finalRows, hunks, added, removed, minor);
  }

  /**
   * Uma linha de contexto. Devolve 1 quando ela é "sem importância" (alinhada
   * como igual mas com texto diferente, o que só acontece com "ignorar
   * espaços"), para o chamador somar — marcar de leve em vez de fingir que as
   * duas linhas são idênticas.
   */
  private static int context(List<Row> rows, List<String> left, List<String> right,
      int oi, int ni) {
    String oldLine = left.get(oi);
    String newLine = right.get(ni);
    boolean same = oldLine.equals(newLine);
    rows.add(new Row(same ? Kind.CONTEXT : Kind.MINOR, oi + 1, ni + 1,
        plain(oldLine), plain(newLine), -1, 0));
    return same ? 0 : 1;
  }

  /**
   * MINOR conta como contexto para o colapso: não é diferença que se navegue.
   * Já as linhas dentro de {@code [keepFrom,keepTo)} nunca colapsam — o laço de
   * blocos abaixo se parte naturalmente em volta delas, sem caso especial.
   */
  private static boolean collapsible(Row row, int keepFrom, int keepTo) {
    if (row.kind() != Kind.CONTEXT && row.kind() != Kind.MINOR) {
      return false;
    }
    int line = row.oldLine() - 1;
    return !(keepFrom >= 0 && line >= keepFrom && line < keepTo);
  }

  private static List<Row> collapseContext(List<Row> rows, int keepFrom, int keepTo) {
    List<Row> out = new ArrayList<>();
    int i = 0;
    while (i < rows.size()) {
      if (!collapsible(rows.get(i), keepFrom, keepTo)) {
        out.add(rows.get(i));
        i++;
        continue;
      }
      int end = i;
      while (end < rows.size() && collapsible(rows.get(end), keepFrom, keepTo)) {
        end++;
      }
      List<Row> block = rows.subList(i, end);
      // No começo e no fim do arquivo só existe um lado a preservar.
      int keepBefore = i == 0 ? 0 : CONTEXT_LINES;
      int keepAfter = end == rows.size() ? 0 : CONTEXT_LINES;
      if (block.size() < Math.max(MIN_COLLAPSIBLE, keepBefore + keepAfter + 1)) {
        out.addAll(block);
      } else {
        out.addAll(block.subList(0, keepBefore));
        int hidden = block.size() - keepBefore - keepAfter;
        out.add(new Row(Kind.GAP, -1, -1, null, null, -1, hidden));
        out.addAll(block.subList(block.size() - keepAfter, block.size()));
      }
      i = end;
    }
    return out;
  }

  // ─── Realce intra-linha ─────────────────────────────────────────────────

  private record Segments(List<Segment> old, List<Segment> neu) {
  }

  private static List<Segment> plain(String text) {
    return List.of(new Segment(text, false));
  }

  private static Segments wordDiff(String oldLine, String newLine) {
    if (oldLine.length() > MAX_WORD_DIFF_CHARS || newLine.length() > MAX_WORD_DIFF_CHARS) {
      return new Segments(plain(oldLine), plain(newLine));
    }
    List<String> a = tokenize(oldLine);
    List<String> b = tokenize(newLine);
    EditList edits = ALGORITHM.diff(LineSequence.COMPARATOR,
        new LineSequence(a), new LineSequence(b));

    List<Segment> oldSeg = new ArrayList<>();
    List<Segment> newSeg = new ArrayList<>();
    int ai = 0;
    int bi = 0;
    for (Edit edit : edits) {
      while (ai < edit.getBeginA()) {
        append(oldSeg, a.get(ai), false);
        append(newSeg, b.get(bi), false);
        ai++;
        bi++;
      }
      for (int k = edit.getBeginA(); k < edit.getEndA(); k++) {
        append(oldSeg, a.get(k), true);
      }
      for (int k = edit.getBeginB(); k < edit.getEndB(); k++) {
        append(newSeg, b.get(k), true);
      }
      ai = edit.getEndA();
      bi = edit.getEndB();
    }
    while (ai < a.size()) {
      append(oldSeg, a.get(ai), false);
      append(newSeg, b.get(bi), false);
      ai++;
      bi++;
    }
    return new Segments(finish(oldSeg, oldLine), finish(newSeg, newLine));
  }

  /** Junta o token no último segmento quando o realce é o mesmo — sem isso a
   * linha viraria um segmento por palavra e o realce ficaria picado. */
  private static void append(List<Segment> segs, String text, boolean changed) {
    if (!segs.isEmpty() && segs.get(segs.size() - 1).changed() == changed) {
      Segment last = segs.remove(segs.size() - 1);
      segs.add(new Segment(last.text() + text, changed));
      return;
    }
    segs.add(new Segment(text, changed));
  }

  private static List<Segment> finish(List<Segment> segs, String fallback) {
    return segs.isEmpty() ? plain(fallback) : segs;
  }

  /**
   * Quebra a linha em três classes de token: palavra (letra/dígito/_), corrida de
   * espaço, e cada outro caractere isolado.
   *
   * <p>Pontuação isolada não é capricho. Agrupando {@code ",} num token só, uma
   * linha que apenas ganhou vírgula no fim marcava a aspa junto — o olho procura
   * uma mudança na aspa que não existe. Separando, marca só a vírgula.
   *
   * <p>Espaço, ao contrário, fica agrupado: indentação de 10 espaços viraria 10
   * tokens e infla o diff sem revelar nada.
   */
  private static List<String> tokenize(String line) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (isWordChar(c) || Character.isWhitespace(c)) {
        boolean word = isWordChar(c);
        int start = i;
        while (i < line.length() && sameClass(line.charAt(i), word)) {
          i++;
        }
        out.add(line.substring(start, i));
      } else {
        out.add(String.valueOf(c));
        i++;
      }
    }
    return out;
  }

  private static boolean sameClass(char c, boolean word) {
    return word ? isWordChar(c) : Character.isWhitespace(c);
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
