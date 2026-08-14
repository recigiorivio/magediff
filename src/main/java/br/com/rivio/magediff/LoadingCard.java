package br.com.rivio.magediff;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;

/**
 * Tela de espera enquanto a comparação é montada.
 *
 * <p>Existe porque comparar duas pastas grandes ou varrer um repositório leva
 * segundos — e sem aviso o app parece travado. Junto com ela vem a parte que
 * importa de verdade: o trabalho saiu da thread da interface. Um "carregando"
 * desenhado por uma thread que está bloqueada não apareceria de jeito nenhum.
 */
public final class LoadingCard extends JPanel {

  private final JLabel title = new JLabel("", SwingConstants.CENTER);
  private final JLabel detail = new JLabel("", SwingConstants.CENTER);

  public LoadingCard(Palette palette) {
    setLayout(new GridBagLayout());
    setBackground(palette.background());

    JPanel column = new JPanel(new BorderLayout(0, 10));
    column.setOpaque(false);
    column.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

    title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
    title.setForeground(palette.text());
    detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 12f));
    detail.setForeground(palette.muted());

    JProgressBar bar = new JProgressBar();
    // Indeterminada: não há como saber a porcentagem sem varrer as pastas duas
    // vezes, e uma barra que mente é pior que uma barra que só diz "estou vivo".
    bar.setIndeterminate(true);
    bar.setPreferredSize(new java.awt.Dimension(260, 6));
    bar.setBorderPainted(false);
    bar.setForeground(palette.currentOutline());
    bar.setBackground(palette.gapBg());

    column.add(title, BorderLayout.NORTH);
    column.add(bar, BorderLayout.CENTER);
    column.add(detail, BorderLayout.SOUTH);
    add(column, new java.awt.GridBagConstraints());
  }

  public void show(String what, String where) {
    title.setText(what);
    detail.setText(where == null ? "" : where);
  }
}
