package br.com.rivio.magediff;

import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JComponent;
import br.com.rivio.magediff.DiffView.Side;

/**
 * Arrastar arquivo do Finder para dentro da janela.
 *
 * <p>O lado é decidido por ONDE o arquivo foi solto, não por um diálogo: soltar
 * na metade esquerda abre à esquerda. É o gesto que a pessoa já fez com a mão —
 * perguntar depois "esquerda ou direita?" desperdiça a informação que o próprio
 * arrasto carrega.
 *
 * <p>Dois arquivos soltos de uma vez preenchem os dois lados, na ordem em que o
 * sistema os entrega.
 */
public final class FileDrop {

  private FileDrop() {
  }

  /**
   * @param divider devolve, para um ponto do componente, se ele está no lado
   *     esquerdo — é o {@code DiffView} que sabe onde a coluna termina
   * @param onDrop recebe (lado, caminho) por arquivo solto
   */
  public static void install(JComponent target, java.util.function.Predicate<Point> divider,
      BiConsumer<Side, Path> onDrop) {
    new DropTarget(target, DnDConstants.ACTION_COPY, new DropTargetListener() {
      @Override
      public void dragEnter(DropTargetDragEvent event) {
        accept(event);
      }

      @Override
      public void dragOver(DropTargetDragEvent event) {
        accept(event);
      }

      @Override
      public void dropActionChanged(DropTargetDragEvent event) {
        accept(event);
      }

      @Override
      public void dragExit(DropTargetEvent event) {
        // Nada a limpar: não há realce de destino a desfazer.
      }

      @Override
      public void drop(DropTargetDropEvent event) {
        if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
          event.rejectDrop();
          return;
        }
        event.acceptDrop(DnDConstants.ACTION_COPY);
        try {
          Object data = event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
          List<?> files = (List<?>) data;
          boolean left = divider.test(event.getLocation());
          int index = 0;
          for (Object item : files) {
            if (!(item instanceof File file) || !file.isFile()) {
              continue;
            }
            // Um arquivo: vai para o lado onde caiu. Dois: preenchem os dois
            // lados, começando pelo lado do ponto de soltura.
            Side side = index == 0
                ? (left ? Side.LEFT : Side.RIGHT)
                : (left ? Side.RIGHT : Side.LEFT);
            onDrop.accept(side, file.toPath());
            index++;
            if (index == 2) {
              break;
            }
          }
          event.dropComplete(true);
        } catch (Exception e) {
          event.dropComplete(false);
        }
      }

      /** Só aceita se houver lista de arquivos; sem isso o cursor mostra "pode
       * soltar" em cima de qualquer arrasto, inclusive texto. */
      private void accept(DropTargetDragEvent event) {
        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
          event.acceptDrag(DnDConstants.ACTION_COPY);
        } else {
          event.rejectDrag();
        }
      }
    });
  }
}
