/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.DoorOrWindow;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeShelfUnit;
import com.eteks.sweethome3d.model.Light;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.SelectionListener;
import com.eteks.sweethome3d.model.ShelfUnit;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.viewcontroller.FurnitureCatalogController;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Floating, non-blocking prompt that waits for template object(s) to be picked in the plan, the
 * furniture list or the catalog. A global input listener and a 300 ms debounce timer wait until
 * multi-selections (Shift/Ctrl click or marquee drag) settle.
 */
final class ObjectPicker {

    /** Modifier / primary mouse button mask indicating an ongoing multi-selection gesture. */
    private static final int SELECTING_MASK = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
            | InputEvent.META_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK;

    private final Home home;
    private final FurnitureCatalogController catalogController;
    private final JDialog dialog;
    private final Timer timer;
    private final SelectionListener selectionListener;
    private final SelectionListener catalogSelectionListener;
    private final AWTEventListener inputListener;
    private boolean selecting;
    private boolean closed;
    private boolean ignoringCatalogClear;
    private boolean lastEventFromCatalog;
    private boolean pickedSuccess;
    private final boolean clearAndRestoreHomeSelection;
    private List<Selectable> savedHomeSelection;

    private ObjectPicker(Window owner, Home home, HomeController homeController, String title,
            String messageText, String hintText, String summaryText, boolean alwaysOnTop,
            boolean clearAndRestoreHomeSelection,
            Consumer<List<HomePieceOfFurniture>> onPicked, Runnable onCancelled) {
        this.home = home;
        this.clearAndRestoreHomeSelection = clearAndRestoreHomeSelection;
        this.catalogController = homeController != null ? homeController.getFurnitureCatalogController() : null;
        if (owner == null) {
            owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        }
        dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        if (alwaysOnTop) {
            dialog.setAlwaysOnTop(true);
        }
        UiKit.applyWindowIcon(dialog);

        timer = new Timer(300, e -> {
            if (selecting || closed) {
                return;
            }
            List<HomePieceOfFurniture> picked = (lastEventFromCatalog && catalogController != null)
                    ? convertCatalogSelection(catalogController.getSelectedFurniture(), homeController)
                    : Collections.<HomePieceOfFurniture>emptyList();
            if (picked.isEmpty()) {
                picked = selectedFurniture(home.getSelectedItems());
            }
            if (picked.isEmpty() && catalogController != null) {
                picked = convertCatalogSelection(catalogController.getSelectedFurniture(), homeController);
            }
            if (!picked.isEmpty()) {
                pickedSuccess = true;
                stopListening();
                dialog.dispose();
                onPicked.accept(picked);
            }
        });
        timer.setRepeats(false);

        selectionListener = ev -> {
            lastEventFromCatalog = false;
            timer.restart();
        };
        catalogSelectionListener = ev -> {
            if (!ignoringCatalogClear) {
                lastEventFromCatalog = true;
                timer.restart();
            }
        };
        inputListener = event -> {
            boolean wasSelecting = selecting;
            selecting = (((InputEvent) event).getModifiersEx() & SELECTING_MASK) != 0;
            if (wasSelecting && !selecting) {
                timer.restart();
            }
        };

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        JLabel message = UiKit.subtitle(UiKit.wrap(messageText, 320));
        message.setAlignmentX(0);
        content.add(message);
        if (hintText != null && !hintText.isEmpty()) {
            content.add(Box.createVerticalStrut(6));
            content.add(UiKit.hint(UiKit.wrap(hintText, 320)));
        }
        if (summaryText != null && !summaryText.isEmpty()) {
            content.add(Box.createVerticalStrut(6));
            content.add(UiKit.hint(summaryText));
        }
        JButton cancel = new JButton(UIManager.getString("OptionPane.cancelButtonText"));
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = UiKit.row(0, cancel);
        buttons.setAlignmentX(0);
        content.add(Box.createVerticalStrut(8));
        content.add(buttons);
        dialog.getContentPane().add(content, BorderLayout.CENTER);

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopListening();
                if (!pickedSuccess && onCancelled != null) {
                    onCancelled.run();
                }
            }
        });
        dialog.pack();
        dialog.setResizable(false);
        if (owner != null && owner.isShowing()) {
            dialog.setLocation(owner.getX() + owner.getWidth() - dialog.getWidth() - 40, owner.getY() + 90);
        } else {
            dialog.setLocationRelativeTo(null);
        }
    }

    static ObjectPicker show(Home home, HomeController homeController, UserPreferences prefs,
            UiKit.Texts t, String title, int floors, int walls,
            Consumer<List<HomePieceOfFurniture>> onPicked) {
        String msg = t.get("pickObjectMessage",
                "Select, in the plan, the furniture list or the catalog, the object to surround with.");
        String hint = t.get("pickObjectHint",
                "The settings open once it is selected. If you select two or more objects (with Shift/Ctrl + click or a rectangle), they will be grouped to surround.");
        String summary = MessageFormat.format(t.get("pickObjectSummary", "Floors: {0}   Walls: {1}"), floors, walls);
        ObjectPicker picker = new ObjectPicker(null, home, homeController, title, msg, hint, summary, false, false, onPicked, null);
        attachAndShow(picker);
        return picker;
    }

    /**
     * Temporarily clears the plan selection so an already-selected piece can be picked in one click,
     * restoring the prior selection on finish or cancel.
     */
    static ObjectPicker showForCorner(Window excludeOwner, Home home, HomeController homeController,
            UiKit.Texts t, String title, Consumer<List<HomePieceOfFurniture>> onPicked, Runnable onCancelled) {
        Window sh3dOwner = null;
        for (Window w : Window.getWindows()) {
            if (w != excludeOwner && w.isShowing() && w instanceof Frame) {
                sh3dOwner = w;
                break;
            }
        }
        String msg = t.get("pickCornerObjectMessage",
                "Select, in the plan, the furniture list or the catalog, the object to place at the corners.");
        String hint = t.get("pickCornerObjectHint",
                "Click an object in the plan, furniture list or catalog. You can also select multiple objects to group them.");
        ObjectPicker picker = new ObjectPicker(sh3dOwner, home, homeController, title, msg, hint, null, true, true, onPicked, onCancelled);
        attachAndShow(picker);
        return picker;
    }

    private static void attachAndShow(ObjectPicker picker) {
        if (picker.clearAndRestoreHomeSelection) {
            picker.savedHomeSelection = new ArrayList<>(picker.home.getSelectedItems());
            if (!picker.savedHomeSelection.isEmpty()) {
                picker.home.setSelectedItems(Collections.<Selectable>emptyList());
            }
        }
        picker.home.addSelectionListener(picker.selectionListener);
        if (picker.catalogController != null) {
            try {
                picker.ignoringCatalogClear = true;
                picker.catalogController.setSelectedFurniture(Collections.<CatalogPieceOfFurniture>emptyList());
            } finally {
                picker.ignoringCatalogClear = false;
            }
            picker.catalogController.addSelectionListener(picker.catalogSelectionListener);
        }
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(picker.inputListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        } catch (SecurityException ignored) {
        }
        picker.dialog.setVisible(true);
    }

    void close() {
        dialog.dispose();
    }

    private void stopListening() {
        if (closed) {
            return;
        }
        closed = true;
        timer.stop();
        home.removeSelectionListener(selectionListener);
        if (catalogController != null) {
            catalogController.removeSelectionListener(catalogSelectionListener);
        }
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener);
        } catch (SecurityException ignored) {
        }
        if (clearAndRestoreHomeSelection && savedHomeSelection != null) {
            List<Selectable> toRestore = savedHomeSelection;
            savedHomeSelection = null;
            Set<Selectable> present = new HashSet<>(home.getSelectableViewableItems());
            present.addAll(home.getFurniture());
            present.addAll(home.getRooms());
            present.addAll(home.getWalls());
            present.addAll(home.getPolylines());
            present.addAll(home.getDimensionLines());
            present.addAll(home.getLabels());
            List<Selectable> valid = new ArrayList<>(toRestore.size());
            for (Selectable s : toRestore) {
                if (present.contains(s)) {
                    valid.add(s);
                }
            }
            home.setSelectedItems(valid);
        }
    }

    private static List<HomePieceOfFurniture> convertCatalogSelection(List<CatalogPieceOfFurniture> catPieces,
            HomeController hc) {
        if (catPieces == null || catPieces.isEmpty()) {
            return Collections.emptyList();
        }
        List<HomePieceOfFurniture> result = new ArrayList<>(catPieces.size());
        for (CatalogPieceOfFurniture cp : catPieces) {
            HomePieceOfFurniture hp = createHomePiece(cp, hc);
            if (hp != null) {
                result.add(hp);
            }
        }
        return result;
    }

    static HomePieceOfFurniture createHomePiece(CatalogPieceOfFurniture piece, HomeController hc) {
        if (piece == null) {
            return null;
        }
        if (hc != null && hc.getFurnitureController() != null) {
            return hc.getFurnitureController().createHomePieceOfFurniture(piece);
        }
        if (piece instanceof DoorOrWindow) {
            return new HomeDoorOrWindow((DoorOrWindow) piece);
        }
        if (piece instanceof Light) {
            return new HomeLight((Light) piece);
        }
        if (piece instanceof ShelfUnit) {
            return new HomeShelfUnit((ShelfUnit) piece);
        }
        return new HomePieceOfFurniture(piece);
    }

    /** Filters selected items to top-level furniture pieces, omitting children of already-selected groups. */
    static List<HomePieceOfFurniture> selectedFurniture(List<? extends Selectable> selected) {
        Set<HomePieceOfFurniture> groupChildren = new HashSet<>();
        for (Selectable item : selected) {
            if (item instanceof HomeFurnitureGroup) {
                for (HomePieceOfFurniture child : ((HomeFurnitureGroup) item).getFurniture()) {
                    SurroundGeometryEngine.collectTemplatePieces(child, groupChildren);
                }
            }
        }
        List<HomePieceOfFurniture> furniture = new ArrayList<>();
        for (Selectable item : selected) {
            if (item instanceof HomePieceOfFurniture && !groupChildren.contains(item)) {
                furniture.add((HomePieceOfFurniture) item);
            }
        }
        return furniture;
    }
}
