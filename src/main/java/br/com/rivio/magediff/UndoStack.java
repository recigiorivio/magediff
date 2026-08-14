package br.com.rivio.magediff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Desfazer/refazer por SNAPSHOT das duas listas de linhas.
 *
 * <p>A alternativa — guardar a operação e invertê-la — é mais econômica e é
 * exatamente onde editor de merge erra: o inverso de "copiei este bloco" depende
 * de índices que já mudaram, e um off-by-one aqui não dá erro, dá arquivo
 * corrompido em silêncio. Copiar duas listas de String de um arquivo que já está
 * inteiro na memória custa nada perto disso.
 *
 * <p>O teto de {@value #MAX_DEPTH} passos evita que uma sessão longa segure
 * memória sem limite; passar dele descarta o passo mais antigo, não o mais novo.
 */
public final class UndoStack {

  private static final int MAX_DEPTH = 100;

  /** Estado completo do que o usuário pode ter alterado. */
  private record Snapshot(String label, List<String> left, List<String> right,
      boolean leftDirty, boolean rightDirty) {
  }

  private final Deque<Snapshot> undo = new ArrayDeque<>();
  private final Deque<Snapshot> redo = new ArrayDeque<>();

  /** Guarda o estado ANTES da alteração. Chamar sempre imediatamente antes de
   * mutar, com o rótulo do que vai acontecer. */
  public void record(String label, TextFile left, TextFile right) {
    undo.push(snapshot(label, left, right));
    if (undo.size() > MAX_DEPTH) {
      undo.removeLast();
    }
    // Um caminho novo apaga o futuro — manter o redo aqui ofereceria "refazer"
    // de uma edição que não existe mais.
    redo.clear();
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public String undoLabel() {
    return undo.isEmpty() ? null : undo.peek().label();
  }

  public String redoLabel() {
    return redo.isEmpty() ? null : redo.peek().label();
  }

  /** Volta um passo. Devolve {@code false} quando não havia o que desfazer. */
  public boolean undo(TextFile left, TextFile right) {
    if (undo.isEmpty()) {
      return false;
    }
    Snapshot target = undo.pop();
    redo.push(snapshot(target.label(), left, right));
    restore(target, left, right);
    return true;
  }

  public boolean redo(TextFile left, TextFile right) {
    if (redo.isEmpty()) {
      return false;
    }
    Snapshot target = redo.pop();
    undo.push(snapshot(target.label(), left, right));
    restore(target, left, right);
    return true;
  }

  public void clear() {
    undo.clear();
    redo.clear();
  }

  private static Snapshot snapshot(String label, TextFile left, TextFile right) {
    return new Snapshot(label,
        new ArrayList<>(left.lines()), new ArrayList<>(right.lines()),
        left.dirty(), right.dirty());
  }

  /**
   * Restaura sobre as MESMAS listas (clear + addAll), nunca trocando a
   * referência: {@code DiffView} e {@code TextFile} seguram essas listas, e
   * substituí-las deixaria a tela apontando para o conteúdo antigo.
   */
  private static void restore(Snapshot snap, TextFile left, TextFile right) {
    left.lines().clear();
    left.lines().addAll(snap.left());
    right.lines().clear();
    right.lines().addAll(snap.right());
    left.setDirty(snap.leftDirty());
    right.setDirty(snap.rightDirty());
  }
}
