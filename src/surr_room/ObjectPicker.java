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
 * Floating, non-blocking prompt that waits for the template object(s) to be picked in the plan, the
 * furniture list or the catalog (catalog pieces become temporary copies). A global input listener and a
 * 300 ms timer wait until a multiple selection (Shift/Ctrl click, rubber band) is finished.
 */
final class ObjectPicker {

    /**
     * Bitmask of selection modifier keys or primary mouse button.
     * When any of these bits are active, the user may still be modifying the selection.
     */
    private static final int SELECTING_MASK = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
            | InputEvent.META_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK;

    /** Reference to the active Sweet Home 3D home model. */
    private final Home home;

    /** Controller for the furniture catalog view, if available. */
    private final FurnitureCatalogController catalogController;

    /** The floating, non-modal dialog window. */
    private final JDialog dialog;

    /** Debounce timer ensuring selections settle before triggering the callback. */
    private final Timer timer;

    /** Listener monitoring selection changes on the Home model (plan / furniture list). */
    private final SelectionListener selectionListener;

    /** Listener monitoring selection changes in the furniture catalog view. */
    private final SelectionListener catalogSelectionListener;

    /** Global AWT event listener tracking keyboard modifiers and mouse button state. */
    private final AWTEventListener inputListener;

    /** Flag indicating whether the user is currently holding a selection modifier or mouse button. */
    private boolean selecting;

    /** Flag indicating whether the picker has already been dismissed or disposed. */
    private boolean closed;

    /** Flag used to suppress false triggers when programmatically clearing the catalog selection on startup. */
    private boolean ignoringCatalogClear;

    /** Flag indicating whether the most recent selection event originated from the catalog view. */
    private boolean lastEventFromCatalog;

    /** Flag indicating whether the user picked an object before the dialog closed. */
    private boolean pickedSuccess;

    /** Whether to temporarily clear Home selection on open and restore it when picking finishes or cancels. */
    private final boolean clearAndRestoreHomeSelection;

    /** Saved Home selection to restore once corner picking completes or is cancelled. */
    private List<Selectable> savedHomeSelection;

    /**
     * Private constructor initializing the floating dialog, event listeners, and layout.
     *
     * @param homeController the parent HomeController (may be null)
     */
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

        // Timer debounces rapid clicks and multi-selection marquee drags (300 ms delay)
        timer = new Timer(300, e -> {
            // Do not fire if the user is still actively dragging or holding modifier keys
            if (selecting || closed) {
                return;
            }
            List<HomePieceOfFurniture> picked = new ArrayList<>();
            // Prefer catalog selection if the most recent event originated from catalog
            if (lastEventFromCatalog && catalogController != null) {
                picked = convertCatalogSelection(catalogController.getSelectedFurniture(), homeController);
            }
            // Fall back to home selection (plan or furniture list)
            if (picked.isEmpty()) {
                picked = selectedFurniture(home.getSelectedItems());
            }
            // Fall back to catalog if home selection was empty
            if (picked.isEmpty() && catalogController != null) {
                picked = convertCatalogSelection(catalogController.getSelectedFurniture(), homeController);
            }
            // If valid furniture pieces were resolved, conclude picking
            if (!picked.isEmpty()) {
                pickedSuccess = true;
                stopListening();
                dialog.dispose();
                onPicked.accept(picked);
            }
        });
        timer.setRepeats(false);

        // Listener for Home model selection changes
        selectionListener = ev -> {
            lastEventFromCatalog = false;
            timer.restart();
        };

        // Listener for Catalog view selection changes
        catalogSelectionListener = ev -> {
            if (!ignoringCatalogClear) {
                lastEventFromCatalog = true;
                timer.restart();
            }
        };

        // Global AWT input listener detecting modifier keys or active mouse drags
        inputListener = event -> {
            boolean wasSelecting = selecting;
            selecting = (((InputEvent) event).getModifiersEx() & SELECTING_MASK) != 0;
            // When modifier/mouse button is released, restart debounce timer
            if (wasSelecting && !selecting) {
                timer.restart();
            }
        };

        // Build the dialog content pane
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

        // Escape key to dismiss dialog
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Clean up listeners when window is closed
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

        // Position near top-right corner of the parent application window
        if (owner != null && owner.isShowing()) {
            dialog.setLocation(owner.getX() + owner.getWidth() - dialog.getWidth() - 40, owner.getY() + 90);
        } else {
            dialog.setLocationRelativeTo(null);
        }
    }

    /**
     * Creates, attaches listeners to, and displays an {@link ObjectPicker} floating dialog.
     *
     * @param prefs          user preferences (optional)
     * @param onPicked       callback invoked when pieces are successfully selected
     */
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
     * Displays an {@link ObjectPicker} floating dialog to choose a custom corner piece directly from
     * the Sweet Home 3D plan, furniture list, or catalog. Temporarily clears the plan selection so even
     * an already-selected piece can be picked in one click, and restores the prior selection on finish/cancel.
     */
    static ObjectPicker showForCorner(Window excludeOwner, Home home, HomeController homeController,
            UiKit.Texts t, String title, Consumer<List<HomePieceOfFurniture>> onPicked, Runnable onCancelled) {
        Window sh3dOwner = null;
        for (Window w : Window.getWindows()) {
            if (w != excludeOwner && w.isShowing() && w instanceof java.awt.Frame) {
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

    /** Registers Home, Catalog, and global AWT input listeners and makes the picker dialog visible. */
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
        } catch (SecurityException ex) {
            // If security manager disallows global listener, fallback to immediate selection events
        }
        picker.dialog.setVisible(true);
    }

    /**
     * Convenience overload for {@link #show} without controller and user preferences.
     *
     * @param onPicked callback invoked when pieces are successfully selected
     */
    static ObjectPicker show(Home home, UiKit.Texts t, String title, int floors, int walls,
            Consumer<List<HomePieceOfFurniture>> onPicked) {
        return show(home, null, null, t, title, floors, walls, onPicked);
    }

    /** Programmatically closes and disposes the picker dialog. */
    void close() {
        dialog.dispose();
    }

    /**
     * Unregisters all selection and AWT event listeners and cancels the debounce timer.
     * Ensures no memory leaks or dangling listeners remain on the Home or Toolkit.
     */
    private void stopListening() {
        if (!closed) {
            closed = true;
            timer.stop();
            home.removeSelectionListener(selectionListener);
            if (catalogController != null) {
                catalogController.removeSelectionListener(catalogSelectionListener);
            }
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener);
            } catch (SecurityException ex) {
                // Ignore if security manager prevented adding the listener initially
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
    }

    /** Converts a list of catalog pieces into concrete {@link HomePieceOfFurniture} instances. */
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

    /**
     * Converts a single catalog piece of furniture into an appropriate {@link HomePieceOfFurniture}
     * subtype (e.g. {@link HomeDoorOrWindow}, {@link HomeLight}, {@link HomeShelfUnit}).
     *
     * @param hc    parent HomeController (if available, uses its furniture controller factory)
     * @return the instantiated HomePieceOfFurniture, or {@code null} if input is null
     */
    static HomePieceOfFurniture createHomePiece(CatalogPieceOfFurniture piece, HomeController hc) {
        if (piece == null) {
            return null;
        }
        if (hc != null && hc.getFurnitureController() != null) {
            return hc.getFurnitureController().createHomePieceOfFurniture(piece);
        }
        if (piece instanceof DoorOrWindow) {
            return new HomeDoorOrWindow((DoorOrWindow) piece);
        } else if (piece instanceof Light) {
            return new HomeLight((Light) piece);
        } else if (piece instanceof ShelfUnit) {
            return new HomeShelfUnit((ShelfUnit) piece);
        }
        return new HomePieceOfFurniture(piece);
    }

    /**
     * Filters the given list of selected items, returning only top-level {@link HomePieceOfFurniture}
     * instances while excluding child pieces already contained inside a selected {@link HomeFurnitureGroup}.
     */
    static List<HomePieceOfFurniture> selectedFurniture(List<? extends Selectable> selected) {
        Set<HomePieceOfFurniture> groupChildren = new HashSet<>();
        // Identify all nested children of selected groups
        for (Selectable item : selected) {
            if (item instanceof HomeFurnitureGroup) {
                for (HomePieceOfFurniture child : ((HomeFurnitureGroup) item).getFurniture()) {
                    SurroundGeometryEngine.collectTemplatePieces(child, groupChildren);
                }
            }
        }
        // Retain only pieces that are not children of another selected group
        List<HomePieceOfFurniture> furniture = new ArrayList<>();
        for (Selectable item : selected) {
            if (item instanceof HomePieceOfFurniture && !groupChildren.contains(item)) {
                furniture.add((HomePieceOfFurniture) item);
            }
        }
        return furniture;
    }
}
