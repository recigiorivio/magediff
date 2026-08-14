package br.com.rivio.magediff;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import br.com.rivio.magediff.DiffEngine.Kind;
import br.com.rivio.magediff.DiffEngine.Row;

/**
 * Faixa vertical com o arquivo inteiro em miniatura: uma marca por linha
 * alterada, na proporção do documento, mais o retângulo do trecho visível.
 *
 * <p>É o que a barra de rolagem não diz. Rolando um arquivo de 3 mil linhas,
 * saber que existem 4 mudanças e que 3 delas estão no fim muda como se navega —
 * e é por isso que todo comparador sério tem essa faixa. Clicar nela salta para
 * o ponto.
 */
public final class Minimap extends JComponent {

  private static final int WIDTH = 14;
  private static final int MIN_MARK_HEIGHT = 2;

  private final DiffView view;
  private final JScrollPane scroll;

  public Minimap(DiffView view, JScrollPane scroll) {
    this.view = view;
    this.scroll = scroll;
    setPreferredSize(new Dimension(WIDTH, 10));
    setOpaque(true);
    setToolTipText("Mapa do arquivo — clique para saltar");
    installMouse();
    // Repinta o indicador de posição enquanto o usuário rola.
    scroll.getVerticalScrollBar().addAdjustmentListener(e -> repaint());
  }

  private void installMouse() {
    MouseAdapter handler = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        scrollToFraction(e.getY());
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        scrollToFraction(e.getY());
      }
    };
    addMouseListener(handler);
    addMouseMotionListener(handler);
  }

  /** Centraliza a viewport na posição clicada (não alinha no topo: clicar numa
   * marca perto do fim deixaria a marca fora da tela). */
  private void scrollToFraction(int y) {
    List<Row> rows = view.rows();
    if (rows.isEmpty() || getHeight() <= 0) {
      return;
    }
    double fraction = Math.max(0, Math.min(1, (double) y / getHeight()));
    int row = (int) Math.round(fraction * (rows.size() - 1));
    int rowHeight = view.rowHeight();
    Rectangle visible = scroll.getViewport().getViewRect();
    int top = row * rowHeight - visible.height / 2;
    top = Math.max(0, Math.min(top, Math.max(0, rows.size() * rowHeight - visible.height)));
    scroll.getViewport().setViewPosition(new java.awt.Point(0, top));
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    Palette p = view.palette();
    g2.setColor(p.gutter());
    g2.fillRect(0, 0, getWidth(), getHeight());
    g2.setColor(p.divider());
    g2.drawLine(0, 0, 0, getHeight());

    List<Row> rows = view.rows();
    if (!rows.isEmpty() && getHeight() > 0) {
      paintMarks(g2, rows);
      paintViewport(g2, rows);
    }
    g2.dispose();
  }

  private void paintMarks(Graphics2D g2, List<Row> rows) {
    double scale = (double) getHeight() / rows.size();
    int half = getWidth() / 2;
    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      if (row.kind() == Kind.CONTEXT || row.kind() == Kind.GAP) {
        continue;
      }
      int y = (int) (r * scale);
      int h = Math.max(MIN_MARK_HEIGHT, (int) scale);
      if (row.kind() == Kind.MINOR) {
        g2.setColor(view.palette().minorBg());
        g2.fillRect(2, y, getWidth() - 4, h);
        continue;
      }
      // REPLACE pinta os dois lados: a metade esquerda em vermelho e a direita
      // em verde, no mesmo eixo das colunas — o mapa fica lendo como a tela.
      if (row.hasOld()) {
        g2.setColor(view.palette().removeAccent());
        g2.fillRect(1, y, half - 1, h);
      }
      if (row.hasNew()) {
        g2.setColor(view.palette().addAccent());
        g2.fillRect(half, y, getWidth() - half - 1, h);
      }
    }
  }

  /** Retângulo do que está visível, para dar a noção de "onde estou". */
  private void paintViewport(Graphics2D g2, List<Row> rows) {
    Rectangle visible = scroll.getViewport().getViewRect();
    int total = rows.size() * view.rowHeight();
    if (total <= 0) {
      return;
    }
    int y = (int) ((double) visible.y / total * getHeight());
    int h = Math.max(6, (int) ((double) visible.height / total * getHeight()));
    Color base = view.palette().currentOutline();
    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 38));
    g2.fillRect(0, y, getWidth(), h);
    g2.setColor(base);
    g2.drawRect(0, y, getWidth() - 1, h - 1);
  }
}
