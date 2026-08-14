package br.com.rivio.magediff;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import br.com.rivio.magediff.GitRepo.Side;

/**
 * Cabeçalho do modo Git: qual repositório, e quais dois lados comparar.
 *
 * <p>Ocupa o mesmo lugar da {@link PathBar} do modo arquivo e é posicionado pela
 * MESMA geometria do diff — o combo de cada lado fica sobre a coluna que ele
 * descreve, então não é preciso ler o rótulo para saber qual é qual.
 */
public final class GitHeader extends JPanel {

  private static final int PATH_ROW = 26;
  private static final int SIDE_ROW = 28;
  private static final int HEIGHT = PATH_ROW + SIDE_ROW;

  public interface Listener {
    void sidesChanged(Side from, Side to);

    void chooseFolder();
  }

  private final DiffView view;
  private final Palette palette;
  private final Listener listener;
  private final JLabel repoLabel = new JLabel();
  private final JComboBox<Side> fromSide = new JComboBox<>();
  private final JComboBox<Side> toSide = new JComboBox<>();
  private final javax.swing.JButton folder;
  private boolean updating;

  public GitHeader(DiffView view, Palette palette, Listener listener) {
    this.view = view;
    this.palette = palette;
    this.listener = listener;
    setLayout(null);
    setPreferredSize(new Dimension(10, HEIGHT));

    repoLabel.setFont(repoLabel.getFont().deriveFont(Font.PLAIN, 11f));
    repoLabel.setForeground(palette.muted());
    folder = Ui.iconButton(Icons.Glyph.OPEN_LEFT, "Escolher outra pasta de repositório",
        Ui.Style.NORMAL, palette, listener::chooseFolder);

    for (JComboBox<Side> combo : List.of(fromSide, toSide)) {
      combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 11.5f));
      combo.setFocusable(false);
      combo.addActionListener(e -> {
        if (!updating && fromSide.getSelectedItem() != null && toSide.getSelectedItem() != null) {
          listener.sidesChanged((Side) fromSide.getSelectedItem(), (Side) toSide.getSelectedItem());
        }
      });
    }
    add(repoLabel);
    add(folder);
    add(fromSide);
    add(toSide);
  }

  /**
   * Preenche os dois combos. O padrão é HEAD à esquerda e pasta de trabalho à
   * direita — a mesma orientação do {@code git diff}: à esquerda o que está
   * gravado, à direita o que você mexeu.
   */
  public void setRepo(String description, List<Side> sides) {
    updating = true;
    repoLabel.setText(description);
    fromSide.removeAllItems();
    toSide.removeAllItems();
    for (Side side : sides) {
      fromSide.addItem(side);
      toSide.addItem(side);
    }
    Side head = sides.stream().filter(s -> !s.isWorkingTree()).findFirst().orElse(sides.get(0));
    fromSide.setSelectedItem(head);
    toSide.setSelectedItem(sides.get(0));
    updating = false;
    doLayout();
    repaint();
  }

  public Side from() {
    return (Side) fromSide.getSelectedItem();
  }

  public Side to() {
    return (Side) toSide.getSelectedItem();
  }

  @Override
  public void doLayout() {
    int pad = 6;
    folder.setBounds(pad, 1, 26, 24);
    repoLabel.setBounds(pad + 32, 1, Math.max(40, getWidth() - 40), 24);

    int y = PATH_ROW + 2;
    int h = SIDE_ROW - 6;
    int leftWidth = Math.max(60, view.xArrow() - pad * 2);
    fromSide.setBounds(pad, y, leftWidth, h);
    int rightStart = view.xGutterRight();
    toSide.setBounds(rightStart + pad, y, Math.max(60, getWidth() - rightStart - pad * 2), h);
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(palette.gutter());
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(palette.divider());
    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
    g.drawLine(0, PATH_ROW, getWidth(), PATH_ROW);
    g.drawLine(view.xArrow(), PATH_ROW + 2, view.xArrow(), getHeight() - 3);
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(super.getPreferredSize().width, HEIGHT);
  }
}
