package br.com.rivio.magediff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import br.com.rivio.magediff.GitRepo.ChangedFile;

/**
 * Lista dos arquivos que diferem entre os dois lados, no modo Git.
 *
 * <p>Mostra a letra da mudança (A/R/M) antes do caminho, colorida como no diff:
 * verde para adicionado, vermelho para removido. Assim dá para varrer a lista
 * sem ler — que é o que se faz quando ela tem 40 itens.
 */
public final class ChangedFilesList extends JPanel {

  private final Palette palette;
  private final DefaultListModel<ChangedFile> model = new DefaultListModel<>();
  private final JList<ChangedFile> list = new JList<>(model);
  private final JLabel summary = new JLabel();

  public ChangedFilesList(Palette palette, Consumer<ChangedFile> onSelect) {
    this.palette = palette;
    setLayout(new BorderLayout());
    setPreferredSize(new Dimension(260, 10));
    setBackground(palette.gutter());

    summary.setFont(summary.getFont().deriveFont(Font.BOLD, 11f));
    summary.setForeground(palette.muted());
    summary.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    add(summary, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setBackground(palette.background());
    list.setFixedCellHeight(22);
    list.setCellRenderer(new Renderer());
    list.addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
        onSelect.accept(list.getSelectedValue());
      }
    });
    JScrollPane scroll = new JScrollPane(list);
    scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 1, palette.divider()));
    add(scroll, BorderLayout.CENTER);
  }

  /** Troca a lista e já seleciona o primeiro: abrir no vazio faria o usuário
   * clicar uma vez só para ver que o app funciona. */
  public void setFiles(List<ChangedFile> files) {
    model.clear();
    for (ChangedFile file : files) {
      model.addElement(file);
    }
    summary.setText(files.isEmpty() ? "Nenhuma diferença"
        : files.size() + (files.size() == 1 ? " arquivo difere" : " arquivos diferem"));
    if (!files.isEmpty()) {
      list.setSelectedIndex(0);
    }
  }

  public void clearSelection() {
    list.clearSelection();
  }

  private final class Renderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> jlist, Object value, int index,
        boolean selected, boolean focused) {
      super.getListCellRendererComponent(jlist, value, index, selected, focused);
      ChangedFile file = (ChangedFile) value;
      String mark = switch (file.kind()) {
        case ADICIONADO -> "A";
        case REMOVIDO -> "R";
        case RENOMEADO -> "→";
        default -> "M";
      };
      Color markColor = switch (file.kind()) {
        case ADICIONADO -> palette.addAccent();
        case REMOVIDO -> palette.removeAccent();
        default -> palette.muted();
      };
      // NOME primeiro, pasta depois. Com caminho fundo, "src/main/java/br/com/…"
      // ocupava a linha toda e o corte comia justamente o nome do arquivo — o
      // único pedaço que identifica o item.
      int slash = file.path().lastIndexOf('/');
      String name = slash < 0 ? file.path() : file.path().substring(slash + 1);
      String folder = slash < 0 ? "" : file.path().substring(0, slash);
      Color folderColor = selected ? new Color(0xE8E8F5) : palette.muted();
      setText(String.format(
          "<html><b style='color:rgb(%d,%d,%d)'>%s</b>&nbsp;&nbsp;%s"
              + "&nbsp;&nbsp;<span style='color:rgb(%d,%d,%d)'>%s</span></html>",
          markColor.getRed(), markColor.getGreen(), markColor.getBlue(), mark, name,
          folderColor.getRed(), folderColor.getGreen(), folderColor.getBlue(), folder));
      setToolTipText(file.kind() == GitRepo.ChangeKind.RENOMEADO
          ? file.oldPath() + "  →  " + file.path() : file.path());
      setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
      setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 6));
      if (!selected) {
        setBackground(palette.background());
        setForeground(palette.text());
      } else {
        setBackground(palette.currentOutline());
        setForeground(Color.WHITE);
      }
      return this;
    }
  }
}
