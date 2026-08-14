package br.com.rivio.magediff;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import br.com.rivio.magediff.GitRepo.CommitInfo;
import br.com.rivio.magediff.GitRepo.Side;

/**
 * Cabeçalho do modo Git: qual repositório, comparar por branch ou por commit, e
 * quais dois lados.
 *
 * <p>Branch e commit são listas separadas porque respondem a perguntas
 * diferentes — "como está na main?" e "o que mudou naquele commit de terça?".
 * Misturar as duas num combo só daria uma lista onde o item que interessa está
 * sempre no meio de dezenas que não interessam.
 *
 * <p>Com um commit selecionado aparece o quadro de detalhes: mensagem completa,
 * autor e data. O resumo no combo é cortado por necessidade (cabe uma linha), e
 * mensagem de commit cortada é justamente onde mora a informação.
 */
public final class GitHeader extends JPanel {

  private static final int PATH_ROW = 26;
  private static final int SIDE_ROW = 28;
  private static final int DETAIL_ROW = 78;

  /** Teto da lista de commits: o combo é para escolher entre os recentes, não
   * para navegar a história inteira. */
  static final int MAX_COMMITS = 60;

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", new Locale("pt", "BR"))
          .withZone(ZoneId.systemDefault());

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
  private final JToggleButton byBranch;
  private final JToggleButton byCommit;
  private final JLabel fromDetail = new JLabel();
  private final JLabel toDetail = new JLabel();
  private final JButton folder;

  private List<Side> branchSides = List.of();
  private List<Side> commitSides = List.of();
  private boolean updating;

  public GitHeader(DiffView view, Palette palette, Listener listener) {
    this.view = view;
    this.palette = palette;
    this.listener = listener;
    setLayout(null);

    repoLabel.setFont(repoLabel.getFont().deriveFont(Font.PLAIN, 11f));
    repoLabel.setForeground(palette.muted());
    folder = Ui.iconButton(Icons.Glyph.OPEN_LEFT, "Escolher outra pasta de repositório",
        Ui.Style.NORMAL, palette, listener::chooseFolder);

    byBranch = Ui.toggle("Branches", "Comparar branches e a pasta de trabalho",
        palette, true, () -> switchMode(true));
    byCommit = Ui.toggle("Commits", "Comparar commits específicos",
        palette, false, () -> switchMode(false));

    for (JComboBox<Side> combo : List.of(fromSide, toSide)) {
      combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 11.5f));
      combo.setFocusable(false);
      combo.addActionListener(e -> onSideChanged());
    }
    for (JLabel detail : List.of(fromDetail, toDetail)) {
      detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
      detail.setForeground(palette.muted());
      detail.setVerticalAlignment(javax.swing.SwingConstants.TOP);
    }

    add(repoLabel);
    add(folder);
    add(byBranch);
    add(byCommit);
    add(fromSide);
    add(toSide);
    add(fromDetail);
    add(toDetail);
  }

  /**
   * Preenche as duas listas. O padrão é HEAD à esquerda e pasta de trabalho à
   * direita — a mesma orientação do {@code git diff}: à esquerda o que está
   * gravado, à direita o que você mexeu.
   */
  public void setRepo(String description, List<Side> sides, List<CommitInfo> commits) {
    repoLabel.setText(description);
    branchSides = sides;
    commitSides = new java.util.ArrayList<>();
    commitSides.add(Side.workingTree());
    for (CommitInfo info : commits) {
      commitSides.add(Side.commit(info));
    }
    applyMode(byCommit.isSelected());
  }

  private void switchMode(boolean branches) {
    byBranch.setSelected(branches);
    byCommit.setSelected(!branches);
    applyMode(!branches);
    onSideChanged();
  }

  private void applyMode(boolean commitMode) {
    updating = true;
    List<Side> options = commitMode ? commitSides : branchSides;
    fromSide.removeAllItems();
    toSide.removeAllItems();
    for (Side side : options) {
      fromSide.addItem(side);
      toSide.addItem(side);
    }
    if (!options.isEmpty()) {
      // Esquerda = o mais antigo dos dois padrões (HEAD ou o commit anterior),
      // direita = a pasta de trabalho. Comparar "o que estava" com "o que está".
      Side left = options.stream().filter(s -> !s.isWorkingTree()).findFirst().orElse(options.get(0));
      fromSide.setSelectedItem(left);
      toSide.setSelectedItem(options.get(0));
    }
    updating = false;
    updateDetails();
    revalidate();
    doLayout();
    repaint();
  }

  private void onSideChanged() {
    if (updating || from() == null || to() == null) {
      return;
    }
    updateDetails();
    listener.sidesChanged(from(), to());
  }

  /** Quadro de detalhes por lado. Fica vazio para branch e pasta de trabalho:
   * mostrar "—" em três linhas ocuparia espaço para não dizer nada. */
  private void updateDetails() {
    fromDetail.setText(describe(from()));
    toDetail.setText(describe(to()));
    fromDetail.setToolTipText(fullMessage(from()));
    toDetail.setToolTipText(fullMessage(to()));
    doLayout();
  }

  /** Mensagem inteira na dica de contexto: o quadro mostra as primeiras linhas,
   * e mensagem de commit longa não cabe em lugar nenhum sem cortar. */
  private static String fullMessage(Side side) {
    if (side == null || side.commit() == null) {
      return null;
    }
    return "<html><pre>" + escape(side.commit().fullMessage()) + "</pre></html>";
  }

  private String describe(Side side) {
    if (side == null || side.commit() == null) {
      return "";
    }
    CommitInfo info = side.commit();
    String when = info.date() == null ? "" : STAMP.format(info.date());
    // Resumo em negrito + as primeiras linhas do corpo. O corpo é onde mora o
    // "por quê" da mudança; mostrar só o resumo devolveria a mesma informação
    // que já está no combo.
    StringBuilder body = new StringBuilder();
    String[] lines = info.fullMessage().split("\n");
    int shown = 0;
    for (int i = 1; i < lines.length && shown < 2; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      body.append("<br>").append(escape(lines[i]));
      shown++;
    }
    return String.format(
        "<html><b>%s</b>%s<br><span style='color:rgb(%d,%d,%d)'>%s &lt;%s&gt;"
            + "  ·  %s  ·  %s</span></html>",
        escape(info.summary()), body,
        palette.muted().getRed(), palette.muted().getGreen(), palette.muted().getBlue(),
        escape(info.author()), escape(info.email()), when, info.shortId());
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  public Side from() {
    return (Side) fromSide.getSelectedItem();
  }

  public Side to() {
    return (Side) toSide.getSelectedItem();
  }

  /** Altura variável: o quadro de detalhes só existe no modo commit. */
  private boolean showingDetails() {
    return byCommit.isSelected() && (!fromDetail.getText().isEmpty()
        || !toDetail.getText().isEmpty());
  }

  private int height() {
    return PATH_ROW + SIDE_ROW + (showingDetails() ? DETAIL_ROW : 0);
  }

  @Override
  public void doLayout() {
    int pad = 6;
    folder.setBounds(pad, 1, 26, 24);
    int toggleWidth = 92;
    byCommit.setBounds(getWidth() - toggleWidth - pad, 1, toggleWidth, 24);
    byBranch.setBounds(getWidth() - toggleWidth * 2 - pad * 2, 1, toggleWidth, 24);
    repoLabel.setBounds(pad + 32, 1,
        Math.max(40, getWidth() - toggleWidth * 2 - pad * 4 - 40), 24);

    int y = PATH_ROW + 2;
    int h = SIDE_ROW - 6;
    int leftWidth = Math.max(60, view.xArrow() - pad * 2);
    int rightStart = view.xGutterRight();
    int rightWidth = Math.max(60, getWidth() - rightStart - pad * 2);
    fromSide.setBounds(pad, y, leftWidth, h);
    toSide.setBounds(rightStart + pad, y, rightWidth, h);

    int dy = PATH_ROW + SIDE_ROW + 2;
    int dh = DETAIL_ROW - 8;
    fromDetail.setBounds(pad + 2, dy, leftWidth, dh);
    toDetail.setBounds(rightStart + pad + 2, dy, rightWidth, dh);
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(palette.gutter());
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(palette.divider());
    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
    g.drawLine(0, PATH_ROW, getWidth(), PATH_ROW);
    g.drawLine(view.xArrow(), PATH_ROW + 2, view.xArrow(), getHeight() - 3);
    if (showingDetails()) {
      g.setColor(palette.background());
      g.fillRect(1, PATH_ROW + SIDE_ROW, getWidth() - 2, DETAIL_ROW - 1);
      g.setColor(palette.divider());
      g.drawLine(0, PATH_ROW + SIDE_ROW, getWidth(), PATH_ROW + SIDE_ROW);
      g.drawLine(view.xArrow(), PATH_ROW + SIDE_ROW, view.xArrow(), getHeight() - 3);
    }
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(super.getPreferredSize().width, height());
  }
}
