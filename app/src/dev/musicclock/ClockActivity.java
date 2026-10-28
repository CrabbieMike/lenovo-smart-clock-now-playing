package dev.musicclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** Home screen for the clock: shows and controls whatever a bridge (on a PC) is playing. */
public class ClockActivity extends Activity {
    static final int PORT = 8765, DISCOVERY_PORT = 8766;

    TextView time, title, artist, source, elapsed, remaining, bigTime, date, weather;
    ImageView art, backdrop;
    LinearLayout root, idle;
    GradientDrawable fill, chip;
    ProgressBar bar;
    ImageButton play;
    SharedPreferences prefs;
    volatile String host;
    volatile boolean running;
    String artId = "";
    volatile boolean playing;
    Boolean online, night; // null until the first check, so the first one always applies

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("clock", MODE_PRIVATE);
        host = prefs.getString("host", null);

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setColorFilter(0x8C000000, PorterDuff.Mode.SRC_ATOP); // darkened so white text reads on any art
        screen.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        root = new LinearLayout(this);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setGravity(Gravity.CENTER_VERTICAL);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(round(0x33FFFFFF, 14));
        art.setClipToOutline(true);
        art.setElevation(dp(16));
        art.setOnLongClickListener(v -> { // hidden way out: long-press the album art to pick another launcher
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            return true;
        });
        root.addView(art, new LinearLayout.LayoutParams(dp(288), dp(288)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), 0, 0, 0);
        root.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL); // stacked: the clock's 800x480 screen is too narrow for a row
        time = text(40, Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setOnLongClickListener(v -> { startActivity(new Intent(Settings.ACTION_SETTINGS)); return true; });
        top.addView(time);
        source = text(13, 0xD9FFFFFF);
        source.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        source.setPadding(dp(12), dp(5), dp(12), dp(5));
        source.setBackground(chip = round(0x26FFFFFF, 14));
        source.setOnClickListener(v -> discover());
        top.addView(source, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        title = text(21, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        artist = text(17, 0x99FFFFFF);
        col.addView(title);
        col.addView(artist);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LayerDrawable track = new LayerDrawable(new Drawable[]{round(0x33FFFFFF, 3),
                new ClipDrawable(fill = round(0xD9FFFFFF, 3), Gravity.START, ClipDrawable.HORIZONTAL)});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        bar.setProgressDrawable(track);
        bar.setPadding(0, dp(12), 0, dp(12)); // 6dp track inside a 30dp touch target
        bar.setOnTouchListener((v, e) -> {
            String h = host;
            if (e.getAction() == MotionEvent.ACTION_UP && h != null) {
                float f = Math.min(Math.max(e.getX() / v.getWidth(), 0), 1);
                new Thread(() -> { try { http(h, "POST", "/seek?f=" + f); poll(); } catch (Exception ignored) { } }).start();
            }
            return true;
        });
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(30));
        barLp.topMargin = dp(4);
        col.addView(bar, barLp);

        FrameLayout times = new FrameLayout(this);
        elapsed = text(12, 0x99FFFFFF);
        remaining = text(12, 0x99FFFFFF);
        times.addView(elapsed, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(remaining, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        times.setPadding(0, 0, 0, 0);
        col.addView(times);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(button("previous", "previous", 34), new LinearLayout.LayoutParams(0, dp(72), 1));
        play = button("play", "play_pause", 48);
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(72), 1));
        controls.addView(button("next", "next", 34), new LinearLayout.LayoutParams(0, dp(72), 1));
        col.addView(controls);

        idle = new LinearLayout(this); // shown instead of the player when nothing is playing
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.setGravity(Gravity.CENTER);
        bigTime = text(120, Color.WHITE);
        bigTime.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        bigTime.setOnLongClickListener(v -> { startActivity(new Intent(Settings.ACTION_SETTINGS)); return true; });
        date = text(24, 0x99FFFFFF);
        weather = text(24, 0x99FFFFFF);
        for (TextView v : new TextView[]{bigTime, date, weather}) {
            v.setGravity(Gravity.CENTER_HORIZONTAL); // vertical LinearLayout children default to full width, which left-aligned them
            idle.addView(v);
        }
        idle.setVisibility(View.GONE);
        screen.addView(idle, new FrameLayout.LayoutParams(-1, -1));

        setContentView(screen);
        source.setText(host == null ? "Choose source" : prefs.getString("name", host));
        if (host == null) discover();
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        new Thread(() -> {
            for (int tick = 0; running; tick++) {
                runOnUiThread(this::tick);
                poll();
                if (tick % 1800 == 0) fetchWeather();
                if (tick % 10 == 9 && !playing) autoSwitch();
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    @Override protected void onPause() { super.onPause(); running = false; }

    @Override public void onBackPressed() { } // we are the home screen

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true; // swallow the up too, or the system adjusts the clock speaker
        String h = host, cmd = code == KeyEvent.KEYCODE_VOLUME_UP ? "/volume_up" : "/volume_down";
        if (h != null) new Thread(() -> { try { http(h, "POST", cmd); } catch (Exception ignored) { } }).start();
        return true; // the clock's own speaker isn't what's playing
    }

    /** Clock text, night dimming, and a small drift every minute so nothing burns in. */
    void tick() {
        Date now = new Date();
        String t = DateFormat.getTimeInstance(DateFormat.SHORT).format(now);
        time.setText(t);
        bigTime.setText(t);
        date.setText(android.text.format.DateFormat.format("EEEE, MMMM d", now));
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY), min = c.get(Calendar.MINUTE);
        boolean n = hour >= 23 || hour < 7;
        if (!Boolean.valueOf(n).equals(night)) {
            night = n;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = n ? 0.5f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
        float dx = dp((min % 3 - 1) * 4), dy = dp((min / 3 % 3 - 1) * 4);
        for (View v : new View[]{root, idle}) { v.setTranslationX(dx); v.setTranslationY(dy); }
    }

    /** Current conditions from Open-Meteo. */
    void fetchWeather() {
        try {
            // Set latitude/longitude to your location (placeholder: New York City)
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.open-meteo.com/v1/forecast?latitude=40.71&longitude=-74.01"
                    + "&current=temperature_2m,weather_code&temperature_unit=fahrenheit").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                String w = sky(cur.getInt("weather_code")) + " " + Math.round(cur.getDouble("temperature_2m")) + "°F";
                runOnUiThread(() -> weather.setText(w));
            } finally { c.disconnect(); }
        } catch (Exception ignored) { } // keep the last reading
    }

    /** WMO weather code to words. */
    static String sky(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67 || (code >= 80 && code <= 82)) return "Rain";
        if (code <= 77 || code == 85 || code == 86) return "Snow";
        return "Thunderstorm";
    }

    /** When the current source is idle, jump to another bridge that is playing. */
    void autoSwitch() {
        for (String[] b : scan()) {
            if (b[0].equals(host)) continue;
            try {
                if (new JSONObject(new String(http(b[0], "GET", "/state"))).optBoolean("playing")) {
                    runOnUiThread(() -> use(b[0], b[1]));
                    return;
                }
            } catch (Exception ignored) { }
        }
    }

    void poll() {
        String h = host;
        if (h == null) return;
        try {
            JSONObject s = new JSONObject(new String(http(h, "GET", "/state")));
            String id = s.optString("art");
            Bitmap bm = null;
            boolean artChanged = !id.equals(artId);
            if (artChanged && !id.isEmpty()) { byte[] a = http(h, "GET", "/art"); bm = BitmapFactory.decodeByteArray(a, 0, a.length); }
            Bitmap fbm = bm;
            // Downscale-then-upscale is a cheap blur; RenderEffect needs Android 12 and the clock runs 10.
            Bitmap small = bm == null ? null : Bitmap.createScaledBitmap(bm, 10, 10, true);
            Bitmap blurred = small == null ? null : Bitmap.createScaledBitmap(small, 200, 120, true);
            int tint = small == null ? Color.WHITE : tint(small);
            playing = s.optBoolean("playing");
            boolean none = s.optBoolean("none");
            runOnUiThread(() -> {
                setOnline(true);
                root.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
                idle.setVisibility(none ? View.VISIBLE : View.GONE);
                backdrop.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
                title.setText(s.optString("title"));
                artist.setText(s.optString("artist"));
                play.setImageDrawable(icon(s.optBoolean("playing") ? "pause" : "play", 48));
                double len = s.optDouble("len", 0), pos = s.optDouble("pos", 0);
                bar.setProgress(len > 0 ? (int) (1000 * pos / len) : 0);
                elapsed.setText(len > 0 ? clock(pos) : "");
                remaining.setText(len > 0 ? "-" + clock(len - pos) : "");
                if (artChanged) {
                    art.setImageBitmap(fbm);
                    backdrop.setImageBitmap(blurred);
                    fill.setColor(tint);
                    chip.setColor(tint & 0x00FFFFFF | 0x40000000);
                    artId = id;
                }
            });
        } catch (Exception e) {
            playing = false;
            runOnUiThread(() -> {
                setOnline(false);
                root.setVisibility(View.VISIBLE);
                idle.setVisibility(View.GONE);
                title.setText("Can't reach " + prefs.getString("name", h));
                artist.setText("Tap the source button to pick another");
            });
        }
    }

    void setOnline(boolean on) {
        if (Boolean.valueOf(on).equals(online)) return;
        online = on;
        source.setAlpha(on ? 1f : 0.4f);
        source.setText(prefs.getString("name", host) + (on ? "" : " offline"));
    }

    /** Average colour of the art, lifted so it shows on the dark backdrop. */
    static int tint(Bitmap small) {
        long r = 0, g = 0, b = 0;
        int n = small.getWidth() * small.getHeight();
        for (int x = 0; x < small.getWidth(); x++)
            for (int y = 0; y < small.getHeight(); y++) { int p = small.getPixel(x, y); r += Color.red(p); g += Color.green(p); b += Color.blue(p); }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.6f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(0xE6, hsv);
    }

    /** Broadcasts for bridges, then lets the user pick one (or type an address). */
    void discover() {
        source.setText("Searching…");
        new Thread(() -> { List<String[]> found = scan(); runOnUiThread(() -> pick(found)); }).start();
    }

    /** Bridges that answer a UDP broadcast within 1.5s, as {ip, name}. Blocks. */
    List<String[]> scan() {
        List<String[]> found = new ArrayList<>();
        try (DatagramSocket sock = new DatagramSocket()) {
                sock.setBroadcast(true);
                sock.setSoTimeout(1500);
                byte[] q = "musicbridge?".getBytes();
                sock.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                byte[] buf = new byte[256];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String r = new String(p.getData(), 0, p.getLength());
                    if (r.startsWith("musicbridge ")) found.add(new String[]{p.getAddress().getHostAddress(), r.substring(12)});
                }
            } catch (SocketTimeoutException done) {
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Search failed: " + e, Toast.LENGTH_LONG).show());
            }
        return found;
    }

    void pick(List<String[]> found) {
        source.setText(host == null ? "Choose source" : prefs.getString("name", host));
        String[] labels = new String[found.size() + 1];
        for (int i = 0; i < found.size(); i++) labels[i] = found.get(i)[1] + "  (" + found.get(i)[0] + ")";
        labels[found.size()] = "Enter address…";
        new AlertDialog.Builder(this).setTitle(found.isEmpty() ? "No bridges found" : "Control music on")
                .setItems(labels, (d, i) -> {
                    if (i < found.size()) use(found.get(i)[0], found.get(i)[1]);
                    else enterAddress();
                }).show();
    }

    void enterAddress() {
        EditText in = new EditText(this);
        in.setHint("192.168.1.20");
        new AlertDialog.Builder(this).setTitle("Bridge IP address").setView(in)
                .setPositiveButton("OK", (d, i) -> {
                    String ip = in.getText().toString().trim();
                    if (!TextUtils.isEmpty(ip)) use(ip, ip);
                }).show();
    }

    void use(String ip, String name) {
        host = ip;
        artId = "";
        online = null; // re-apply chip text and alpha for the new source
        prefs.edit().putString("host", ip).putString("name", name).apply();
        source.setText(name);
    }

    ImageButton button(String kind, String cmd, int sizeDp) {
        ImageButton b = new ImageButton(this);
        b.setImageDrawable(icon(kind, sizeDp));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)); // borderless, like iOS transport controls
        b.setOnClickListener(v -> {
            String h = host;
            if (h != null) new Thread(() -> { try { http(h, "POST", "/" + cmd); poll(); } catch (Exception ignored) { } }).start();
        });
        return b;
    }

    TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Drawn rather than font glyphs, which fall back to mismatched fonts (the pause one especially). */
    Drawable icon(String kind, int sizeDp) {
        Path p = new Path(); // 24x24 box
        switch (kind) {
            case "play": tri(p, 7, 5, 20); break;
            case "pause":
                p.addRoundRect(new RectF(6, 5, 10, 19), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(14, 5, 18, 19), 1.5f, 1.5f, Path.Direction.CW); break;
            case "next": tri(p, 2, 6, 12); tri(p, 12, 6, 22); break;
            case "previous": tri(p, 12, 6, 2); tri(p, 22, 6, 12); break;
        }
        ShapeDrawable d = new ShapeDrawable(new PathShape(p, 24, 24));
        d.getPaint().setColor(Color.WHITE);
        d.getPaint().setAntiAlias(true);
        // Baked to a bitmap: the clock's GPU smears touching path triangles into a gradient.
        Bitmap bm = Bitmap.createBitmap(dp(sizeDp), dp(sizeDp), Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bm.getWidth(), bm.getHeight());
        d.draw(new android.graphics.Canvas(bm));
        return new android.graphics.drawable.BitmapDrawable(getResources(), bm);
    }

    /** Triangle pointing from x=base to x=tip, spanning y=top..24-top. */
    static void tri(Path p, float base, float top, float tip) {
        p.moveTo(base, top); p.lineTo(tip, 12); p.lineTo(base, 24 - top); p.close();
    }


    static String clock(double sec) { int s = (int) Math.max(0, sec); return String.format("%d:%02d", s / 60, s % 60); }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static byte[] http(String host, String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + path).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        c.setRequestMethod(method);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
}
