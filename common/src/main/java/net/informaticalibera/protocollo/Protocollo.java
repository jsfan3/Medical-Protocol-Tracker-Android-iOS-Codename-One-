package net.informaticalibera.protocollo;

import static com.codename1.ui.CN.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import com.codename1.components.InfiniteProgress;
import com.codename1.components.SpanLabel;
import com.codename1.components.ToastBar;
import com.codename1.io.ConnectionRequest;
import com.codename1.io.JSONParser;
import com.codename1.io.Log;
import com.codename1.io.NetworkManager;
import com.codename1.io.Preferences;
import com.codename1.io.Storage;
import com.codename1.io.Util;
import com.codename1.system.Lifecycle;
import com.codename1.ui.*;
import com.codename1.ui.layouts.BorderLayout;
import com.codename1.ui.layouts.BoxLayout;
import com.codename1.ui.layouts.FlowLayout;

/**
 * Personal app to track a customizable protocol.
 *
 *  • Downloads (and refreshes) a JSON file into Storage
 *  • Displays items with N checkboxes each
 *  • Saves/restores state with Preferences
 *  • Resets daily checks at midnight
 */
public class Protocollo extends Lifecycle {

    private static final String JSON_URL = "https://example.com/path/to/alimentazione.json";
    private static final String JSON_FILE = "alimentazione.json";
    private static final String PREF_LAST_RESET = "last_reset_date";

    @Override
    public void runApp() {
        /* ---------- Loading screen ---------- */
        Form splash = new Form(new BorderLayout());
        splash.add(BorderLayout.CENTER, new SpanLabel("Loading..."));
        splash.show();

        /* Ensure JSON exists locally, then build the UI */
        ensureJsonFile(this::buildMainForm);
    }

    /* **********************************************************************
     * Download & caching
     * *********************************************************************/

    /** Ensures the JSON exists in Storage, then calls onReady.run() */
    private void ensureJsonFile(Runnable onReady) {
        if (!Storage.getInstance()
            .exists(JSON_FILE)) {
            String json = "{\n" + "  \"title\": \"Loading...\",\n" + "  \"description\": \"Downloading protocol...\",\n" + "  \"items\": [\n" + "  ],\n" +
                "  \"footer\": \"\"\n" + "}";

            try (OutputStream os = Storage.getInstance()
                .createOutputStream(JSON_FILE); OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
                writer.write(json);
                writer.flush();   // optional: close() already does this
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }
        onReady.run();
        silentRefreshJson();
    }

    /** Refreshes the JSON in background: overwrite only if changed */
    private void silentRefreshJson() {
        ConnectionRequest req = new ConnectionRequest() {

            protected void readResponse(InputStream in) throws IOException {
                /* Remote content */
                String newString = Util.readToString(in);

                /* Compare with local content */
                String oldString;
                try (InputStream old = Storage.getInstance()
                    .createInputStream(JSON_FILE)) {
                    oldString = Util.readToString(old);
                }

                if (!newString.equals(oldString)) {
                    /* Write the new file */
                    try (OutputStream out = Storage.getInstance()
                        .createOutputStream(JSON_FILE)) {
                        out.write(newString.getBytes("UTF-8"));
                    }
                    callSerially(() -> {
                        ToastBar.showInfoMessage("Protocol updated");
                        buildMainForm();          // reload UI
                    });
                } else {
                    callSerially(() -> ToastBar.showInfoMessage("Protocol is already up to date"));
                }
            }
        };
        req.setUrl(JSON_URL);
        req.setFailSilently(true);
        NetworkManager.getInstance()
            .addToQueue(req);
    }

    /* **********************************************************************
     * Main UI
     * *********************************************************************/

    private void buildMainForm() {
        try {
            /* --- 1. Read JSON from Storage --- */
            Map<String, Object> root;
            try (InputStream in = Storage.getInstance()
                .createInputStream(JSON_FILE)) {
                root = new JSONParser().parseJSON(new InputStreamReader(in, "UTF-8"));
            }

            String title = (String) root.get("title");
            String description = (String) root.get("description");
            String footer = (String) root.get("footer") + "\n\n" + "Day: " + (giorni() + 1) + "\n\n";

            @SuppressWarnings("unchecked") List<Map<String, Object>> dieta = (List<Map<String, Object>>) (root.containsKey("items") ? root.get("items") : root.get("dieta"));

            /* --- 2. Daily reset of checkmarks (once per day) --- */
            resetDailyIfNeeded(dieta);

            /* --- 3. Build the Form --- */
            Form frm = new Form(title, BoxLayout.y());
            frm.setScrollableY(true);

            /* Toolbar and manual refresh */
            // frm.getToolbar().addCommandToSideMenu("Refresh protocol", null, evt -> silentRefreshJson());

            /* Description */
            SpanLabel descLbl = new SpanLabel(description);
            descLbl.setUIID("Description");
            frm.add(descLbl);

            /* Items with checkboxes */
            boolean isFirst = true;
            for (Map<String, Object> item : dieta) {
                addDietaRow(frm, item, isFirst);
                isFirst = false;
            }

            /* Footer */
            SpanLabel footerLbl = new SpanLabel(footer);
            footerLbl.setUIID("Footer");
            frm.add(footerLbl);

            frm.revalidate();
            frm.show();
        } catch (Exception ex) {
            Storage.getInstance().deleteStorageFile(JSON_FILE);
            Dialog.show("Error", "Corrupted JSON file removed. Close and reopen the app. If the issue persists, check the server-side JSON.", "OK", null);
            Log.e(ex);
            return;
        }
    }

    /** Adds one row (“times × CheckBox + text”) to the Form */
    private void addDietaRow(Form target, Map<String, Object> item, boolean isFirst) {
        int id = ((Number) item.get("id")).intValue();
        int times = ((Number) item.get("times")).intValue();
        String txt = (String) item.get("item");

        Container row = new Container(BoxLayout.y());
        Container cbs = new Container(new FlowLayout(Component.LEFT));
        cbs.setUIID("CheckListCnt");
        if (isFirst) {
            row.setUIID("FirstRow");
        } else {
            row.setUIID("Row");
        }

        int checked = 0;

        for (int i = 0; i < times; i++) {
            CheckBox cb = new CheckBox();
            cb.setUIID("ChecklistCB");

            final String prefKey = "checkbox_" + id + "_" + i;
            boolean saved = Preferences.get(prefKey, false);
            cb.setSelected(saved);
            if (saved)
                checked++;

            cb.addActionListener(ev -> {
                Preferences.set(prefKey, cb.isSelected());
                updateRowCompleted(cbs, row, times);
            });

            cbs.add(cb);
        }
        row.add(cbs);

        SpanLabel lbl = new SpanLabel(txt);
        lbl.setUIID("ItemLabel");
        row.add(lbl);

        if (checked == times)
            markRowCompleted(row, true);

        target.add(row);
    }

    /** Highlights (or clears) the row when all checkboxes are selected */
    private void updateRowCompleted(Container cbs, Container row, int times) {
        for (int i = 0; i < times; i++) {
            if (!((CheckBox) cbs.getComponentAt(i)).isSelected()) {
                markRowCompleted(row, false);
                return;
            }
        }
        markRowCompleted(row, true);
    }

    private void markRowCompleted(Container row, boolean completed) {
        if (completed) {
            row.setUIID("RowCompleted");
        } else {
            row.setUIID("Row");
        }
        CN.callSerially(() -> {
            row.revalidateLater();
        });
    }

    /* **********************************************************************
     * Daily reset
     * *********************************************************************/

    /** Clears checks if a new day has started */
    private void resetDailyIfNeeded(List<Map<String, Object>> dieta) {
        long last = Preferences.get(PREF_LAST_RESET, 0L);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayMidnight = cal.getTime()
            .getTime();

        if (last < todayMidnight) {
            /* Delete the preference for every protocol checkbox */
            for (Map<String, Object> it : dieta) {
                int id = ((Number) it.get("id")).intValue();
                int times = ((Number) it.get("times")).intValue();
                for (int i = 0; i < times; i++) {
                    Preferences.delete("checkbox_" + id + "_" + i);
                }
            }
            Preferences.set(PREF_LAST_RESET, System.currentTimeMillis());
        }
    }

    /* **********************************************************************
     * Utilities
     * *********************************************************************/

    private void handleDownloadError(boolean showDialog, String msg) {
        if (showDialog)
            Dialog.show("Error", msg, "OK", null);
        else
            ToastBar.showErrorMessage(msg);
    }

    /**
     * Returns the number of full days elapsed since the configured base date.
     *
     * Assumes the current date is after the base date; otherwise an
     * IllegalStateException ("wrong time") is thrown.
     *
     * @return elapsed days (zero or positive)
     * @throws IllegalStateException if the current date is before the base date
     */
    public static int giorni() {
        final long MILLIS_PER_DAY = 86_400_000L;           // 24 h in milliseconds

        // Set the timezone (Europe/Rome by default)
        TimeZone tz = TimeZone.getTimeZone("Europe/Rome");

        // Compute milliseconds from local midnight of the base date
        Calendar base = Calendar.getInstance(tz);
        base.set(Calendar.YEAR, 2025);
        base.set(Calendar.MONTH, Calendar.NOVEMBER);
        base.set(Calendar.DAY_OF_MONTH, 12);        // month is 0-based
        base.set(Calendar.MILLISECOND, 0);

        long diffMillis = System.currentTimeMillis() - base.getTime()
            .getTime();

        // If diffMillis is negative, the device clock is before the base date.
        if (diffMillis < 0) {
            throw new IllegalStateException("Wrong time: the current date is before the configured base date");
        }

        // Integer division -> elapsed days (drops remaining hours/minutes)
        return (int) (diffMillis / MILLIS_PER_DAY);
    }

}
