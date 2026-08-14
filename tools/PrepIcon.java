import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Transforma o logo.jpg num PNG de ícone: só o quadrado arredondado, com os
 * cantos transparentes.
 *
 * <p>Não usa preenchimento por inundação a partir dos cantos. Aquilo tira o
 * fundo plano, mas deixa o halo — o brilho e a sombra que o desenho tem em volta
 * do quadrado ficam num tom intermediário e sobram como uma auréola clara. Como
 * a forma é conhecida (quadrado arredondado), recortar por MÁSCARA dá resultado
 * limpo e previsível em vez de depender de tolerância de cor.
 *
 * <p>Os limites do quadrado são achados pelos pixels ESCUROS: o desenho é escuro
 * e o fundo/halo é claro, então luminância separa os dois sem ambiguidade.
 *
 * <p>Também grava um {@code .ico} ao lado, quando o terceiro argumento é dado:
 * o Windows não aceita PNG nem ICNS como ícone de executável.
 *
 * <p>Uso: java PrepIcon entrada.jpg saida.png [saida.ico]
 */
public final class PrepIcon {

  /** Abaixo disso é desenho; acima é fundo ou halo. */
  private static final int DARK_LUMA = 150;

  /** Proporção do raio do canto, próxima do arredondamento do próprio desenho. */
  private static final double RADIUS_RATIO = 0.225;

  /**
   * Quanto apertar a máscara para dentro. O desenho tem uma sombra logo fora da
   * borda roxa; ela é escura o suficiente para entrar na caixa achada por
   * luminância e sobrava como um filete cinza na beirada. Recortar um pouco mais
   * apertado tira a sombra sem comer a borda.
   */
  private static final double INSET_RATIO = 0.022;

  public static void main(String[] args) throws Exception {
    BufferedImage src = ImageIO.read(new File(args[0]));
    int w = src.getWidth();
    int h = src.getHeight();

    int minX = w;
    int minY = h;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int rgb = src.getRGB(x, y);
        int luma = (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF))
            / 1000;
        if (luma < DARK_LUMA) {
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }
    }
    if (maxX < 0) {
      throw new IllegalStateException("nenhum pixel escuro: imagem inesperada");
    }

    // Quadrado: ícone esticado fica torto em qualquer tamanho que o sistema use.
    int side = Math.max(maxX - minX + 1, maxY - minY + 1);
    int cx = (minX + maxX + 1) / 2;
    int cy = (minY + maxY + 1) / 2;
    int left = Math.max(0, Math.min(w - side, cx - side / 2));
    int top = Math.max(0, Math.min(h - side, cy - side / 2));

    int inset = (int) Math.round(side * INSET_RATIO);
    left += inset;
    top += inset;
    side -= inset * 2;

    BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    // A máscara é desenhada PRIMEIRO e a imagem entra com SRC_IN: só o que cai
    // dentro do arredondado sobrevive, com a borda suavizada pelo antialias.
    double radius = side * RADIUS_RATIO;
    g.fill(new RoundRectangle2D.Double(0, 0, side, side, radius * 2, radius * 2));
    g.setComposite(AlphaComposite.SrcIn);
    g.drawImage(src.getSubimage(left, top, Math.min(side, w - left), Math.min(side, h - top)),
        0, 0, null);
    g.dispose();

    ImageIO.write(out, "png", new File(args[1]));
    System.out.printf("%dx%d → quadrado em (%d,%d) lado %d, raio %.0f%n",
        w, h, left, top, side, radius);

    if (args.length > 2) {
      writeIco(out, new File(args[2]));
      System.out.println("ico: " + args[2]);
    }
  }

  /**
   * Escreve um .ico com as imagens em PNG dentro.
   *
   * <p>O formato ICO original guarda bitmap cru; desde o Vista ele também aceita
   * PNG embutido, que é o que qualquer Windows suportado hoje lê — e evita ter
   * que escrever DIB com máscara de transparência à mão, que é onde ícone
   * gerado por script normalmente sai com fundo preto.
   */
  private static void writeIco(BufferedImage square, File target) throws Exception {
    int[] sizes = {16, 32, 48, 64, 128, 256};
    byte[][] pngs = new byte[sizes.length][];
    for (int i = 0; i < sizes.length; i++) {
      BufferedImage scaled = new BufferedImage(sizes[i], sizes[i],
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D sg = scaled.createGraphics();
      sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      sg.drawImage(square, 0, 0, sizes[i], sizes[i], null);
      sg.dispose();
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      ImageIO.write(scaled, "png", buffer);
      pngs[i] = buffer.toByteArray();
    }

    try (java.io.DataOutputStream out = new java.io.DataOutputStream(
        new java.io.BufferedOutputStream(new java.io.FileOutputStream(target)))) {
      writeShortLE(out, 0);                    // reservado
      writeShortLE(out, 1);                    // tipo 1 = ícone
      writeShortLE(out, sizes.length);
      int offset = 6 + 16 * sizes.length;
      for (int i = 0; i < sizes.length; i++) {
        // 256 é gravado como 0 no diretório do ICO — o campo tem 1 byte.
        out.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
        out.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
        out.writeByte(0);                      // paleta
        out.writeByte(0);                      // reservado
        writeShortLE(out, 1);                  // planos
        writeShortLE(out, 32);                 // bits por pixel
        writeIntLE(out, pngs[i].length);
        writeIntLE(out, offset);
        offset += pngs[i].length;
      }
      for (byte[] png : pngs) {
        out.write(png);
      }
    }
  }

  private static void writeShortLE(java.io.DataOutputStream out, int value) throws Exception {
    out.writeByte(value & 0xFF);
    out.writeByte((value >> 8) & 0xFF);
  }

  private static void writeIntLE(java.io.DataOutputStream out, int value) throws Exception {
    out.writeByte(value & 0xFF);
    out.writeByte((value >> 8) & 0xFF);
    out.writeByte((value >> 16) & 0xFF);
    out.writeByte((value >> 24) & 0xFF);
  }
}
