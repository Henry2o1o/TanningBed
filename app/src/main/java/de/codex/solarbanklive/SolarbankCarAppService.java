package de.codex.solarbanklive;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.validation.HostValidator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Read-only, driver-optimized view of the latest SOLIX measurements. */
public final class SolarbankCarAppService extends CarAppService {
    @Override
    public HostValidator createHostValidator() {
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @Override
    public Session onCreateSession() {
        return new Session() {
            @Override
            public Screen onCreateScreen(Intent intent) {
                return new SolarbankScreen(getCarContext());
            }
        };
    }

    private static final class SolarbankScreen extends Screen {
        private static final long REFRESH_MS = 30_000L;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable refresh = new Runnable() {
            @Override public void run() {
                invalidate();
                handler.postDelayed(this, REFRESH_MS);
            }
        };

        SolarbankScreen(androidx.car.app.CarContext context) {
            super(context);
        }

        @Override
        public ListTemplate onGetTemplate() {
            SharedPreferences prefs = getCarContext().getSharedPreferences("cloud_session", Context.MODE_PRIVATE);
            Snapshot snapshot = Snapshot.read(prefs);
            ItemList.Builder items = new ItemList.Builder();
            items.addItem(row("☀  Solarleistung", snapshot.pv, "PV-Erzeugung"));
            items.addItem(row("▣  Akkustand", snapshot.soc, snapshot.batteryFlow));
            items.addItem(row("⌂  Hausverbrauch", snapshot.home, "Aktueller Verbrauch"));
            items.addItem(row("⚡  Netz", snapshot.grid, snapshot.gridDirection));
            items.addItem(row("◷  Datenstand", snapshot.updated, snapshot.connection));

            Action refreshAction = new Action.Builder()
                    .setTitle("Aktualisieren")
                    .setOnClickListener(this::invalidate)
                    .build();
            ActionStrip actions = new ActionStrip.Builder().addAction(refreshAction).build();
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, REFRESH_MS);
            return new ListTemplate.Builder()
                    .setTitle(snapshot.siteName.isEmpty() ? "Sonnenbank Auto" : snapshot.siteName)
                    .setHeaderAction(Action.APP_ICON)
                    .setActionStrip(actions)
                    .setSingleList(items.build())
                    .build();
        }

        private static Row row(String title, String value, String secondary) {
            String line = value == null || value.isEmpty() ? "—" : value;
            if (secondary != null && !secondary.isEmpty()) line += " · " + secondary;
            return new Row.Builder().setTitle(title).addText(line).build();
        }
    }

    private static final class Snapshot {
        String siteName = "", pv = "—", soc = "—", batteryFlow = "Akku-Leistung nicht verfügbar";
        String home = "—", grid = "—", gridDirection = "Netzwert nicht verfügbar";
        String updated = "Noch keine Messung", connection = "Bitte zuerst am Telefon anmelden";

        static Snapshot read(SharedPreferences prefs) {
            Snapshot s = new Snapshot();
            String raw = prefs.getString("last_scene", "");
            if (raw.isEmpty()) return s;
            try {
                JSONObject scene = new JSONObject(raw);
                JSONObject bank = scene.optJSONObject("solarbank_info");
                if (bank == null) bank = new JSONObject();
                JSONObject gridInfo = scene.optJSONObject("grid_info");
                if (gridInfo == null) gridInfo = new JSONObject();
                JSONObject first = bank.optJSONArray("solarbank_list") == null
                        ? null : bank.optJSONArray("solarbank_list").optJSONObject(0);
                if (first == null) first = new JSONObject();

                double pv = number(bank, "total_photovoltaic_power", "total_pv_input_power");
                double home = number(scene, "home_load_power");
                double gridImport = number(gridInfo, "grid_to_home_power");
                double gridExport = number(gridInfo, "photovoltaic_to_grid_power");
                double batterySigned = number(bank, "total_battery_power_signed");
                double charge = number(bank, "total_charging_power", "charging_power");
                double discharge = number(bank, "total_discharging_power", "discharging_power");

                JSONArray devices = new JSONArray(prefs.getString("mqtt_devices", "[]"));
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.optJSONObject(i);
                    if (device == null || !"AE103".equalsIgnoreCase(device.optString("device_pn", device.optString("product_code", "")))) continue;
                    String serial = device.optString("device_sn", device.optString("sn", ""));
                    JSONObject live = new JSONObject(prefs.getString("mqtt_device_" + serial, "{}"));
                    if (live.has("photovoltaic_power")) pv = live.optDouble("photovoltaic_power", pv);
                    if (live.has("battery_power_signed")) batterySigned = live.optDouble("battery_power_signed", batterySigned);
                    if (live.has("home_demand")) home = live.optDouble("home_demand", home);
                    if (live.has("grid_power_signed")) {
                        double signed = live.optDouble("grid_power_signed", 0);
                        gridImport = Math.max(0, signed);
                        gridExport = Math.max(0, -signed);
                    }
                    long received = live.optLong("received_at", 0);
                    if (received > 0) s.updated = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(new Date(received));
                    break;
                }

                if ((!Double.isFinite(home) || home <= 0.5)
                        && (Double.isFinite(pv) || Double.isFinite(batterySigned)
                        || Double.isFinite(charge) || Double.isFinite(discharge)
                        || Double.isFinite(gridImport) || Double.isFinite(gridExport))) {
                    double batteryBalance = Double.isFinite(batterySigned)
                            ? batterySigned
                            : (Double.isFinite(discharge) ? Math.abs(discharge) : 0)
                            - (Double.isFinite(charge) ? Math.abs(charge) : 0);
                    double gridBalance = (Double.isFinite(gridImport) ? gridImport : 0)
                            - (Double.isFinite(gridExport) ? gridExport : 0);
                    double estimatedHome = (Double.isFinite(pv) ? pv : 0) + batteryBalance + gridBalance;
                    if (estimatedHome > 0.5) home = estimatedHome;
                }

                if (pv >= 0) s.pv = watts(pv);
                double soc = number(bank, "total_battery_power");
                if (!Double.isFinite(soc)) soc = number(first, "battery_soc");
                if (Double.isFinite(soc)) {
                    if (soc > 0 && soc <= 1) soc *= 100;
                    if (soc >= 0 && soc <= 100) s.soc = String.format(Locale.GERMANY, "%.0f %%", soc);
                }
                if (Double.isFinite(batterySigned)) {
                    double w = Math.abs(batterySigned);
                    s.batteryFlow = (batterySigned > 0 ? "Entlädt " : "Lädt ") + watts(w);
                } else if (Double.isFinite(charge) || Double.isFinite(discharge)) {
                    s.batteryFlow = "Lädt " + watts(Math.max(0, charge)) + " · Entlädt " + watts(Math.max(0, discharge));
                }
                if (Double.isFinite(home) && home >= 0) s.home = watts(home);
                if (Double.isFinite(gridImport) || Double.isFinite(gridExport)) {
                    double in = Double.isFinite(gridImport) ? gridImport : 0;
                    double out = Double.isFinite(gridExport) ? gridExport : 0;
                    if (in >= out) {
                        s.grid = watts(in - out);
                        s.gridDirection = "Netzbezug";
                    } else {
                        s.grid = watts(out - in);
                        s.gridDirection = "Einspeisung";
                    }
                }
                String cloudTime = bank.optString("updated_time", scene.optString("updated_time", ""));
                if (s.updated.equals("Noch keine Messung") && !cloudTime.isEmpty()) s.updated = cloudTime;
                s.siteName = prefs.getString("site_name", "");
                s.connection = prefs.getString("auth_token", "").isEmpty() ? "Anker-Anmeldung fehlt" : "Letzte gespeicherte Messwerte";
                if (!s.updated.equals("Noch keine Messung")) s.connection = "Aktualisiert aus Anker SOLIX / MQTT";
            } catch (Exception ignored) {
                s.connection = "Messwerte konnten nicht gelesen werden";
            }
            return s;
        }

        private static double number(JSONObject object, String... keys) {
            for (String key : keys) {
                if (!object.has(key)) continue;
                Object value = object.opt(key);
                try {
                    double parsed = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
                    if (Double.isFinite(parsed)) return parsed;
                } catch (Exception ignored) { }
            }
            return Double.NaN;
        }

        private static String watts(double value) {
            return String.format(Locale.GERMANY, "%.0f W", value);
        }
    }
}
