package br.com.rivio.magediff;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import br.com.rivio.magediff.DiffEngine.Kind;
import br.com.rivio.magediff.DiffEngine.Result;
import br.com.rivio.magediff.DiffEngine.Row;
import br.com.rivio.magediff.DiffEngine.Segment;

/**
 * Desenha os dois arquivos lado a lado, com as setas de merge na coluna do meio.
 *
 * <p>Um único componente pinta os DOIS lados — não são dois painéis sincronizados
 * por listener de scroll. A rolagem vertical fica igual por construção, o que
 * elimina a classe de bug em que um lado desliza um pixel/uma linha do outro.
 *
 * <p>A rolagem horizontal, ao contrário, é interna ({@code hOffset} aplicado só
 * dentro das duas colunas de texto, com clip). Se ela viesse do
 * {@code JScrollPane}, uma linha longa empurraria as setas para fora da tela — e
 * seta que não se alcança não serve para merge.
 */
public final class DiffView extends JComponent implements Scrollable {

  /** Chamado quando o usuário clica numa seta. */
  public interface MergeListener {
    void copyHunk(int hunkIndex, boolean toRight);
  }

  /** Chamado quando o usuário clica no ↶ que aparece onde a última cópia caiu. */
  public interface UndoListener {
    void undoLastMerge();
  }

  /** Qual das duas colunas de texto está selecionada. */
  public enum Side {
    LEFT, RIGHT
  }

  private static final int GUTTER_PAD = 10;
  private static final int TEXT_PAD = 6;
  private static final int ARROW_W = 46;
  private static final int ARROW_SIZE = 16;
  private static final int TAB_WIDTH = 4;
  private static final int MIN_TEXT_W = 120;

  private Palette palette = Palette.forCurrentTheme();
  private Result result = new Result(List.of(), List.of(), 0, 0, 0);
  private List<String> leftLines = List.of();
  private List<String> rightLines = List.of();
  /** hunkIndex → índice da row onde ele começa (onde as setas são desenhadas). */
  private int[] hunkFirstRow = new int[0];

  private int rowHeight = 16;
  private int charWidth = 8;
  private int gutterWidth = 58;
  private int maxTextWidth = MIN_TEXT_W;
  private int hOffset;
  private int currentHunk = -1;
  private int hotHunk = -1;
  private boolean hotToRight;
  private Side selSide;
  private int selAnchor = -1;
  private int selFocus = -1;

  private MergeListener mergeListener;
  private UndoListener undoListener;
  private Runnable selectionListener;
  /**
   * Row onde a última cópia de bloco caiu, ou -1. Depois de copiar, aquele bloco
   * deixa de existir (os dois lados ficaram iguais) e a seta desaparece — sem
   * essa marca, o desfazer só existiria na barra, longe de onde a ação
   * aconteceu. Some no próximo diff que não venha de uma cópia.
   */
  private int undoRow = -1;
  /** Faixa de rows do rastro da última cópia (inclusive). -1 quando não há. */
  private int mergeFromRow = -1;
  private int mergeToRow = -1;

  public DiffView() {
    setOpaque(true);
    setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    setAutoscrolls(true);
    installMouse();
  }

  public void setMergeListener(MergeListener listener) {
    this.mergeListener = listener;
  }

  public void setUndoListener(UndoListener listener) {
    this.undoListener = listener;
  }

  /**
   * Marca o rastro da última cópia: a faixa de rows que ficou igual nos dois
   * lados, com o ↶ de desfazer na primeira. {@code -1, -1} apaga.
   */
  public void setMergeMark(int fromRow, int toRow) {
    mergeFromRow = fromRow;
    mergeToRow = toRow;
    undoRow = fromRow;
    repaint();
  }

  public void clearMergeMark() {
    setMergeMark(-1, -1);
  }

  /**
   * Em que row está a linha {@code line} (0-based) de um dos lados — usado para
   * pôr o ↶ exatamente onde o conteúdo copiado foi cair.
   */
  public int rowOfLine(Side side, int line) {
    List<Row> rows = result.rows();
    for (int r = 0; r < rows.size(); r++) {
      int at = side == Side.LEFT ? rows.get(r).oldLine() : rows.get(r).newLine();
      if (at == line + 1) {
        return r;
      }
    }
    return -1;
  }

  public void setSelectionListener(Runnable listener) {
    this.selectionListener = listener;
  }

  public void setPalette(Palette palette) {
    this.palette = palette;
    repaint();
  }

  public Palette palette() {
    return palette;
  }

  public void setData(Result result, List<String> leftLines, List<String> rightLines) {
    this.result = result;
    this.leftLines = leftLines;
    this.rightLines = rightLines;
    this.hunkFirstRow = indexHunks(result);
    if (currentHunk >= result.hunks().size()) {
      currentHunk = result.hunks().isEmpty() ? -1 : result.hunks().size() - 1;
    }
    recomputeMetrics();
    revalidate();
    repaint();
  }

  private static int[] indexHunks(Result result) {
    int[] first = new int[result.hunks().size()];
    for (int i = 0; i < first.length; i++) {
      first[i] = -1;
    }
    List<Row> rows = result.rows();
    for (int r = 0; r < rows.size(); r++) {
      int h = rows.get(r).hunkIndex();
      if (h >= 0 && h < first.length && first[h] < 0) {
        first[h] = r;
      }
    }
    return first;
  }

  private void recomputeMetrics() {
    FontMetrics fm = getFontMetrics(getFont());
    rowHeight = fm.getHeight();
    charWidth = Math.max(1, fm.charWidth('0'));
    int digits = Math.max(3, String.valueOf(Math.max(leftLines.size(), rightLines.size())).length());
    gutterWidth = digits * charWidth + GUTTER_PAD * 2;

    int widest = MIN_TEXT_W;
    for (Row row : result.rows()) {
      widest = Math.max(widest, measure(fm, row.oldSegments()));
      widest = Math.max(widest, measure(fm, row.newSegments()));
    }
    maxTextWidth = widest + TEXT_PAD * 2;
    hOffset = Math.min(hOffset, Math.max(0, maxTextWidth - textWidth()));
  }

  private int measure(FontMetrics fm, List<Segment> segments) {
    if (segments == null) {
      return 0;
    }
    int w = 0;
    for (Segment seg : segments) {
      w += fm.stringWidth(display(seg.text()));
    }
    return w;
  }

  /** Tabulação é expandida só para desenhar — o conteúdo em memória (e o que é
   * gravado) continua com o {@code \t} original. */
  private static String display(String text) {
    return text.indexOf('\t') < 0 ? text : text.replace("\t", " ".repeat(TAB_WIDTH));
  }

  // ─── Geometria das colunas ──────────────────────────────────────────────

  public int gutterWidth() {
    return gutterWidth;
  }

  public static int arrowWidth() {
    return ARROW_W;
  }

  /** Largura de UMA coluna de texto. As duas são iguais de propósito: colunas de
   * larguras diferentes fazem o olho comparar posições que não correspondem. */
  public int textWidth() {
    int available = getWidth() - gutterWidth * 2 - ARROW_W;
    return Math.max(MIN_TEXT_W, available / 2);
  }

  public int xTextLeft() {
    return gutterWidth;
  }

  public int xArrow() {
    return gutterWidth + textWidth();
  }

  public int xGutterRight() {
    return xArrow() + ARROW_W;
  }

  public int xTextRight() {
    return xGutterRight() + gutterWidth;
  }

  public int contentWidth() {
    return maxTextWidth;
  }

  public int hOffset() {
    return hOffset;
  }

  public void setHOffset(int offset) {
    int clamped = Math.max(0, Math.min(offset, Math.max(0, maxTextWidth - textWidth())));
    if (clamped != hOffset) {
      hOffset = clamped;
      repaint();
    }
  }

  public int rowHeight() {
    return rowHeight;
  }

  // ─── Seleção de linhas (base do copiar/colar) ───────────────────────────

  public Side selectedSide() {
    return selSide;
  }

  public boolean hasSelection() {
    return selSide != null && selAnchor >= 0 && selFocus >= 0;
  }

  public void clearSelection() {
    selSide = null;
    selAnchor = -1;
    selFocus = -1;
    repaint();
  }

  private int selFrom() {
    return Math.min(selAnchor, selFocus);
  }

  private int selTo() {
    return Math.max(selAnchor, selFocus);
  }

  /**
   * Converte a seleção (em rows) para um intervalo semiaberto de LINHAS do
   * arquivo daquele lado — que é a unidade que copiar/colar manipulam.
   *
   * <p>Quando a seleção não contém nenhuma linha do lado escolhido (selecionar,
   * pela esquerda, um bloco que só existe à direita), devolve um intervalo vazio
   * na posição correspondente: colar ali INSERE em vez de substituir. É a mesma
   * ideia do intervalo semiaberto dos blocos.
   *
   * <p>Se a seleção atravessa um separador de linhas escondidas, as linhas
   * escondidas entram — "da linha 5 até a 200" quer dizer isso, mesmo com o meio
   * colapsado.
   */
  public int[] selectionLineRange(Side side) {
    if (!hasSelection()) {
      return new int[] {0, 0};
    }
    boolean oldSide = side == Side.LEFT;
    List<Row> rows = result.rows();
    int from = -1;
    int to = -1;
    for (int r = selFrom(); r <= selTo() && r < rows.size(); r++) {
      int line = oldSide ? rows.get(r).oldLine() : rows.get(r).newLine();
      if (line > 0) {
        if (from < 0) {
          from = line - 1;
        }
        to = line;
      }
    }
    if (from >= 0) {
      return new int[] {from, to};
    }
    // Nenhuma linha deste lado na seleção: ancora depois da última linha
    // numerada acima. O colapso sempre mantém 3 linhas de contexto reais em
    // volta de uma mudança, então essa varredura não atravessa um separador.
    int anchor = 0;
    for (int r = selFrom() - 1; r >= 0; r--) {
      int line = oldSide ? rows.get(r).oldLine() : rows.get(r).newLine();
      if (line > 0) {
        anchor = line;
        break;
      }
    }
    return new int[] {anchor, anchor};
  }

  /** Seleciona o intervalo de rows correspondente a um bloco — usado depois de
   * colar/desfazer para a seleção não apontar para lugar nenhum. */
  public void selectRows(Side side, int fromRow, int toRow) {
    selSide = side;
    selAnchor = fromRow;
    selFocus = toRow;
    repaint();
  }

  // ─── Navegação e seleção ────────────────────────────────────────────────

  public int currentHunk() {
    return currentHunk;
  }

  public int hunkCount() {
    return result.hunks().size();
  }

  public void goToHunk(int index) {
    if (index < 0 || index >= hunkFirstRow.length) {
      return;
    }
    currentHunk = index;
    int row = hunkFirstRow[index];
    if (row >= 0) {
      // Três linhas de folga em volta pra a mudança não encostar na borda.
      int y = Math.max(0, (row - 3) * rowHeight);
      int h = rowHeight * 7;
      scrollRectToVisible(new Rectangle(0, y, 1, h));
    }
    notifySelection();
    repaint();
  }

  public void nextHunk() {
    goToHunk(Math.min(hunkCount() - 1, currentHunk + 1));
  }

  public void previousHunk() {
    goToHunk(Math.max(0, currentHunk - 1));
  }

  private void notifySelection() {
    if (selectionListener != null) {
      selectionListener.run();
    }
  }

  private void installMouse() {
    MouseAdapter handler = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (undoRow >= 0 && undoBounds() != null && undoBounds().contains(e.getX(), e.getY())) {
          if (undoListener != null) {
            undoListener.undoLastMerge();
          }
          return;
        }
        int hunk = hunkAtArrow(e.getX(), e.getY(), true);
        if (hunk >= 0) {
          currentHunk = hunk;
          notifySelection();
          if (mergeListener != null) {
            mergeListener.copyHunk(hunk, true);
          }
          return;
        }
        hunk = hunkAtArrow(e.getX(), e.getY(), false);
        if (hunk >= 0) {
          currentHunk = hunk;
          notifySelection();
          if (mergeListener != null) {
            mergeListener.copyHunk(hunk, false);
          }
          return;
        }
        int row = rowAt(e.getY());
        if (row < 0) {
          return;
        }
        Side side = sideAt(e.getX());
        if (side != null) {
          // Shift estende a seleção existente; clique simples recomeça.
          if (e.isShiftDown() && selSide == side && selAnchor >= 0) {
            selFocus = row;
          } else {
            selSide = side;
            selAnchor = row;
            selFocus = row;
          }
        }
        int h = result.rows().get(row).hunkIndex();
        if (h >= 0) {
          currentHunk = h;
        }
        notifySelection();
        repaint();
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        if (selSide == null) {
          return;
        }
        int row = rowAt(e.getY());
        if (row >= 0 && row != selFocus) {
          selFocus = row;
          notifySelection();
          repaint();
        }
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        int right = hunkAtArrow(e.getX(), e.getY(), true);
        int left = right >= 0 ? -1 : hunkAtArrow(e.getX(), e.getY(), false);
        int hot = right >= 0 ? right : left;
        boolean toRight = right >= 0;
        if (hot != hotHunk || toRight != hotToRight) {
          hotHunk = hot;
          hotToRight = toRight;
          repaint();
        }
      }

      @Override
      public void mouseExited(MouseEvent e) {
        if (hotHunk >= 0) {
          hotHunk = -1;
          repaint();
        }
      }
    };
    addMouseListener(handler);
    addMouseMotionListener(handler);
  }

  private int rowAt(int y) {
    int row = y / Math.max(1, rowHeight);
    return row >= 0 && row < result.rows().size() ? row : -1;
  }

  /** Em qual coluna de texto (ou calha) o x caiu. {@code null} na coluna das
   * setas — clicar ali é ação de merge, não de seleção. */
  private Side sideAt(int x) {
    if (x < xArrow()) {
      return Side.LEFT;
    }
    if (x >= xGutterRight()) {
      return Side.RIGHT;
    }
    return null;
  }

  private Rectangle arrowBounds(int hunkIndex, boolean toRight) {
    if (hunkIndex < 0 || hunkIndex >= hunkFirstRow.length || hunkFirstRow[hunkIndex] < 0) {
      return null;
    }
    int y = hunkFirstRow[hunkIndex] * rowHeight + (rowHeight - ARROW_SIZE) / 2;
    int x = toRight ? xArrow() + 4 : xArrow() + ARROW_W - ARROW_SIZE - 4;
    return new Rectangle(x, y, ARROW_SIZE, ARROW_SIZE);
  }

  /** Área clicável do ↶, no centro da coluna das setas. */
  private Rectangle undoBounds() {
    if (undoRow < 0 || undoRow >= result.rows().size()) {
      return null;
    }
    int y = undoRow * rowHeight + (rowHeight - ARROW_SIZE) / 2;
    return new Rectangle(xArrow() + (ARROW_W - ARROW_SIZE) / 2, y, ARROW_SIZE, ARROW_SIZE);
  }

  private int hunkAtArrow(int x, int y, boolean toRight) {
    if (x < xArrow() || x > xArrow() + ARROW_W) {
      return -1;
    }
    int row = y / Math.max(1, rowHeight);
    for (int h = 0; h < hunkFirstRow.length; h++) {
      if (hunkFirstRow[h] == row) {
        Rectangle bounds = arrowBounds(h, toRight);
        return bounds != null && bounds.contains(x, y) ? h : -1;
      }
    }
    return -1;
  }

  // ─── Pintura ────────────────────────────────────────────────────────────

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setFont(getFont());

    Rectangle clip = g2.getClipBounds();
    g2.setColor(palette.background());
    g2.fillRect(clip.x, clip.y, clip.width, clip.height);

    List<Row> rows = result.rows();
    int firstRow = Math.max(0, clip.y / rowHeight);
    int lastRow = Math.min(rows.size() - 1, (clip.y + clip.height) / rowHeight);
    FontMetrics fm = g2.getFontMetrics();
    int baselineOffset = fm.getAscent();

    for (int r = firstRow; r <= lastRow; r++) {
      paintRow(g2, rows.get(r), r, r * rowHeight, baselineOffset);
    }

    // Divisórias verticais por cima das linhas, uma vez só.
    g2.setColor(palette.divider());
    g2.drawLine(xTextLeft(), clip.y, xTextLeft(), clip.y + clip.height);
    g2.drawLine(xArrow(), clip.y, xArrow(), clip.y + clip.height);
    g2.drawLine(xGutterRight(), clip.y, xGutterRight(), clip.y + clip.height);
    g2.drawLine(xTextRight(), clip.y, xTextRight(), clip.y + clip.height);

    for (int h = 0; h < hunkFirstRow.length; h++) {
      if (hunkFirstRow[h] >= firstRow - 1 && hunkFirstRow[h] <= lastRow + 1) {
        paintArrows(g2, h);
      }
    }
    paintMergeTrail(g2);
    paintCurrentOutline(g2);
    paintUndoMark(g2);
    paintSelection(g2);
    g2.dispose();
  }

  /**
   * Rastro da última cópia: uma faixa clara com um filete na borda esquerda,
   * atravessando as duas colunas. Sem ela, o trecho mesclado vira contexto igual
   * a qualquer outro e some no meio do arquivo — some justamente no momento em
   * que você mais quer saber onde ele estava.
   */
  private void paintMergeTrail(Graphics2D g2) {
    if (mergeFromRow < 0 || mergeToRow < mergeFromRow) {
      return;
    }
    int y = mergeFromRow * rowHeight;
    int h = (mergeToRow - mergeFromRow + 1) * rowHeight;
    Color base = palette.currentOutline();
    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 22));
    g2.fillRect(0, y, getWidth(), h);
    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 150));
    g2.fillRect(0, y, 3, h);
    g2.fillRect(xGutterRight(), y, 3, h);
  }

  /** O ↶ onde a última cópia caiu — desfazer ao alcance do olho que acabou de
   * ver a mudança acontecer, em vez de só na barra. */
  private void paintUndoMark(Graphics2D g2) {
    Rectangle b = undoBounds();
    if (b == null) {
      return;
    }
    g2.setColor(palette.gapBg());
    g2.fillRoundRect(b.x - 3, b.y - 2, b.width + 6, b.height + 4, 6, 6);
    g2.setColor(palette.currentOutline());
    g2.drawRoundRect(b.x - 3, b.y - 2, b.width + 6, b.height + 4, 6, 6);
    Icons.draw(g2, Icons.Glyph.UNDO, b.x + b.width / 2, b.y + b.height / 2, 13,
        palette.currentOutline());
  }

  /** Véu translúcido sobre o lado selecionado — por cima das cores de add/remove
   * para não escondê-las: quem seleciona ainda precisa ver o que mudou ali. */
  private void paintSelection(Graphics2D g2) {
    if (!hasSelection()) {
      return;
    }
    int x = selSide == Side.LEFT ? 0 : xGutterRight();
    int w = (selSide == Side.LEFT ? xArrow() : getWidth() - xGutterRight()) - 1;
    int y = selFrom() * rowHeight;
    int h = (selTo() - selFrom() + 1) * rowHeight;
    Color base = palette.currentOutline();
    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 42));
    g2.fillRect(x, y, w, h);
    g2.setColor(base);
    g2.drawRect(x, y, w, h - 1);
  }

  private void paintRow(Graphics2D g2, Row row, int rowIndex, int y, int baselineOffset) {
    if (row.kind() == Kind.GAP) {
      g2.setColor(palette.gapBg());
      g2.fillRect(0, y, getWidth(), rowHeight);
      g2.setColor(palette.divider());
      g2.drawLine(0, y, getWidth(), y);
      g2.drawLine(0, y + rowHeight - 1, getWidth(), y + rowHeight - 1);
      g2.setColor(palette.muted());
      String label = row.hidden() + (row.hidden() == 1 ? " linha igual" : " linhas iguais");
      g2.drawString("⋯ " + label, xTextLeft() + TEXT_PAD, y + baselineOffset);
      return;
    }

    paintGutter(g2, 0, row.oldLine(), gutterColor(row, true), y, baselineOffset);
    paintText(g2, xTextLeft(), row.oldSegments(), bodyColor(row, true),
        palette.removeAccent(), y, baselineOffset);
    paintGutter(g2, xGutterRight(), row.newLine(), gutterColor(row, false), y, baselineOffset);
    paintText(g2, xTextRight(), row.newSegments(), bodyColor(row, false),
        palette.addAccent(), y, baselineOffset);
  }

  private Color bodyColor(Row row, boolean oldSide) {
    if (oldSide ? !row.hasOld() : !row.hasNew()) {
      return palette.absentBg();
    }
    if (row.kind() == Kind.CONTEXT) {
      return palette.background();
    }
    // "Diferença sem importância": tom próprio, mais fraco que add/remove — quem
    // ligou "ignorar espaços" quer ver que existe, não ser puxado para ela.
    if (row.kind() == Kind.MINOR) {
      return palette.minorBg();
    }
    return oldSide ? palette.removeBg() : palette.addBg();
  }

  private Color gutterColor(Row row, boolean oldSide) {
    boolean present = oldSide ? row.hasOld() : row.hasNew();
    if (!present) {
      return palette.absentBg();
    }
    if (row.kind() == Kind.CONTEXT) {
      return palette.gutter();
    }
    if (row.kind() == Kind.MINOR) {
      return palette.minorBg();
    }
    return oldSide ? palette.removeGutter() : palette.addGutter();
  }

  private void paintGutter(Graphics2D g2, int x, int line, Color bg, int y, int baselineOffset) {
    g2.setColor(bg);
    g2.fillRect(x, y, gutterWidth, rowHeight);
    if (line > 0) {
      g2.setColor(palette.gutterText());
      String label = String.valueOf(line);
      int w = g2.getFontMetrics().stringWidth(label);
      g2.drawString(label, x + gutterWidth - GUTTER_PAD - w, y + baselineOffset);
    }
  }

  private void paintText(Graphics2D g2, int x, List<Segment> segments, Color bg, Color accent,
      int y, int baselineOffset) {
    int w = textWidth();
    g2.setColor(bg);
    g2.fillRect(x, y, w, rowHeight);
    if (segments == null) {
      return;
    }
    Shape oldClip = g2.getClip();
    g2.clipRect(x, y, w, rowHeight);
    FontMetrics fm = g2.getFontMetrics();
    int cursor = x + TEXT_PAD - hOffset;
    for (Segment seg : segments) {
      String shown = display(seg.text());
      int segWidth = fm.stringWidth(shown);
      if (seg.changed()) {
        g2.setColor(accent);
        g2.fillRect(cursor, y, segWidth, rowHeight);
      }
      g2.setColor(palette.text());
      g2.drawString(shown, cursor, y + baselineOffset);
      cursor += segWidth;
    }
    g2.setClip(oldClip);
  }

  private void paintArrows(Graphics2D g2, int hunkIndex) {
    paintArrow(g2, hunkIndex, true);
    paintArrow(g2, hunkIndex, false);
  }

  private void paintArrow(Graphics2D g2, int hunkIndex, boolean toRight) {
    Rectangle b = arrowBounds(hunkIndex, toRight);
    if (b == null) {
      return;
    }
    boolean hot = hunkIndex == hotHunk && toRight == hotToRight;
    boolean current = hunkIndex == currentHunk;
    g2.setColor(hot || current ? palette.arrowHot() : palette.arrowIdle());

    Path2D.Double tri = new Path2D.Double();
    int mid = b.y + b.height / 2;
    if (toRight) {
      tri.moveTo(b.x, b.y + 2);
      tri.lineTo(b.x + b.width - 2, mid);
      tri.lineTo(b.x, b.y + b.height - 2);
    } else {
      tri.moveTo(b.x + b.width - 2, b.y + 2);
      tri.lineTo(b.x + 2, mid);
      tri.lineTo(b.x + b.width - 2, b.y + b.height - 2);
    }
    tri.closePath();
    g2.fill(tri);
  }

  private void paintCurrentOutline(Graphics2D g2) {
    if (currentHunk < 0 || currentHunk >= hunkFirstRow.length) {
      return;
    }
    int from = hunkFirstRow[currentHunk];
    if (from < 0) {
      return;
    }
    int to = from;
    List<Row> rows = result.rows();
    while (to + 1 < rows.size() && rows.get(to + 1).hunkIndex() == currentHunk) {
      to++;
    }
    g2.setColor(palette.currentOutline());
    int y = from * rowHeight;
    int h = (to - from + 1) * rowHeight;
    g2.drawRect(0, y, xArrow() - 1, h - 1);
    g2.drawRect(xGutterRight(), y, getWidth() - xGutterRight() - 1, h - 1);
  }

  // ─── Scrollable ─────────────────────────────────────────────────────────

  @Override
  public Dimension getPreferredSize() {
    int rows = Math.max(1, result.rows().size());
    return new Dimension(400, rows * rowHeight);
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return new Dimension(1000, 40 * rowHeight);
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL ? rowHeight : charWidth * 4;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL ? visible.height - rowHeight : visible.width / 2;
  }

  /** {@code true} de propósito: o scroll horizontal é interno (ver o javadoc da
   * classe), então o {@code JScrollPane} nunca deve criar o dele. */
  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  /**
   * {@code true} quando o conteúdo é mais curto que a viewport: assim o
   * componente ocupa a área toda e as divisórias de coluna vão até embaixo, em
   * vez de pararem na última linha e deixarem um retângulo solto.
   */
  @Override
  public boolean getScrollableTracksViewportHeight() {
    return getParent() instanceof javax.swing.JViewport viewport
        && viewport.getHeight() > getPreferredSize().height;
  }

  /** Índices das rows visíveis — usado só pelo cabeçalho e por testes. */
  public List<Row> rows() {
    return new ArrayList<>(result.rows());
  }
}
