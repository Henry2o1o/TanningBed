#!/usr/bin/env python3
"""Sparkasse CSV viewer. Read-only and fully local."""
from __future__ import annotations

import csv
import datetime as dt
import json
import os
import sys
import tkinter as tk
from collections import defaultdict
from tkinter import filedialog, messagebox, ttk


BG = "#080d1b"
PANEL = "#111a2d"
PANEL2 = "#17233a"
EDGE = "#344a70"
TEXT = "#f0f3ff"
MUTED = "#a5b2cf"
GREEN = "#55df8a"
BLUE = "#56d8f4"
RED = "#ff718d"
GOLD = "#ffc85b"
FONT = "DejaVu Sans"


def parse_money(raw: str) -> float:
    value = (raw or "").strip().replace("€", "").replace(" ", "")
    if not value:
        return 0.0
    # German CSV exports use comma decimals and may use dot thousands separators.
    if "," in value:
        value = value.replace(".", "").replace(",", ".")
    return float(value)


def parse_date(raw: str) -> dt.date | None:
    raw = (raw or "").strip()
    for fmt in ("%d.%m.%Y", "%d.%m.%y", "%Y-%m-%d", "%d/%m/%Y"):
        try:
            return dt.datetime.strptime(raw, fmt).date()
        except ValueError:
            pass
    return None


def money(value: float) -> str:
    s = f"{abs(value):,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")
    return ("−" if value < -0.004 else "") + s + " €"


def read_csv(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        sample = f.read(4096)
        f.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=";,\t")
        except csv.Error:
            dialect = csv.excel
            dialect.delimiter = ";"
        reader = csv.DictReader(f, dialect=dialect)
        rows = []
        for row in reader:
            d = parse_date(row.get("Buchungstag", "")) or parse_date(row.get("Wertstellungstag", ""))
            if not d:
                continue
            try:
                amount = parse_money(row.get("Betrag", ""))
            except ValueError:
                continue
            try:
                balance = parse_money(row.get("Saldo", ""))
            except ValueError:
                balance = 0.0
            purpose = " ".join((row.get(f"Verwendungszweckzeile {i}", "") or "").strip() for i in range(1, 15)).strip()
            rows.append({
                "date": d, "amount": amount, "balance": balance,
                "text": (row.get("Buchungstext", "") or "").strip(),
                "name": (row.get("Begünstigter/Absender - Name", "") or "").strip(),
                "category": (row.get("Kategorie", "") or "").strip() or "Ohne Kategorie",
                "purpose": purpose,
            })
    rows.sort(key=lambda x: x["date"], reverse=True)
    return rows


class SparkasseApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Sparkasse · Kontoübersicht")
        self.geometry("1280x900")
        self.minsize(960, 700)
        self.configure(bg=BG)
        self.rows: list[dict] = []
        self.path = ""
        self.last_directory = self._read_last_directory()
        self.sort_column = "date"
        self.sort_descending = True
        self.period = tk.StringVar(value="Alle Buchungen")
        self.search = tk.StringVar()
        self._style()
        self._build()
        self.bind("<Control-o>", lambda _e: self.open_file())
        if len(sys.argv) > 1 and os.path.isfile(sys.argv[1]):
            self.load_file(sys.argv[1])

    def _style(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=TEXT,
                        borderwidth=0, rowheight=36, font=(FONT, 10))
        style.configure("Treeview.Heading", background=PANEL2, foreground="#b9c9e7",
                        relief="flat", font=(FONT, 9, "bold"), padding=(8, 11))
        style.map("Treeview", background=[("selected", "#24496d")], foreground=[("selected", TEXT)])
        style.configure("Vertical.TScrollbar", background=PANEL2, troughcolor=BG, bordercolor=BG)
        style.configure("Dark.TCombobox", fieldbackground=PANEL2, background=PANEL2, foreground=TEXT,
                        arrowcolor=GREEN, bordercolor=EDGE, padding=7)
        style.map("Dark.TCombobox",
                  fieldbackground=[("readonly", PANEL2), ("!disabled", PANEL2)],
                  foreground=[("readonly", TEXT), ("!disabled", TEXT)],
                  selectbackground=[("readonly", PANEL2)],
                  selectforeground=[("readonly", TEXT)])

    def _build(self):
        shell = tk.Frame(self, bg=BG)
        shell.pack(fill="both", expand=True, padx=24, pady=20)
        header = tk.Frame(shell, bg=BG)
        header.pack(fill="x", pady=(0, 18))
        logo = tk.Canvas(header, width=54, height=54, bg=BG, highlightthickness=0)
        logo.pack(side="left", padx=(0, 13))
        self._draw_logo(logo)
        titles = tk.Frame(header, bg=BG)
        titles.pack(side="left", anchor="w")
        tk.Label(titles, text="Sparkasse", bg=BG, fg=TEXT, font=(FONT, 22, "bold")).pack(anchor="w")
        tk.Label(titles, text="Kontoübersicht · lokal und nur lesend", bg=BG, fg=MUTED,
                 font=(FONT, 10)).pack(anchor="w", pady=(2, 0))
        self.file_label = tk.Label(header, text="Keine Datei geöffnet", bg=BG, fg=MUTED,
                                   font=(FONT, 9))
        self.file_label.pack(side="right", padx=(10, 14))
        self.open_btn = tk.Button(header, text="＋  CSV öffnen", command=self.open_file,
                                  bg="#12334a", fg=GREEN, activebackground="#194967", activeforeground=TEXT,
                                  relief="flat", bd=0, padx=17, pady=10, font=(FONT, 10, "bold"), cursor="hand2")
        self.open_btn.pack(side="right")

        self.content = tk.Frame(shell, bg=BG)
        self.content.pack(fill="both", expand=True)
        self.empty = tk.Frame(self.content, bg=PANEL, highlightbackground=EDGE, highlightthickness=1)
        self.empty.place(relx=.5, rely=.48, anchor="center", width=600, height=290)
        tk.Label(self.empty, text="▤", bg=PANEL, fg=GREEN, font=(FONT, 38)).pack(pady=(28, 6))
        tk.Label(self.empty, text="Öffne deinen Kontoexport", bg=PANEL, fg=TEXT,
                 font=(FONT, 18, "bold")).pack()
        tk.Label(self.empty, text="Die CSV wird direkt von deinem Gerät gelesen.\nSie wird weder verändert noch in die Anwendung kopiert.",
                 bg=PANEL, fg=MUTED, font=(FONT, 10), justify="center").pack(pady=10)
        tk.Button(self.empty, text="CSV-Datei auswählen", command=self.open_file,
                  bg="#12334a", fg=GREEN, activebackground="#194967", activeforeground=TEXT,
                  relief="flat", padx=18, pady=10, font=(FONT, 10, "bold"), cursor="hand2").pack(pady=8)

        self.dashboard = tk.Frame(self.content, bg=BG)
        self.account_hero = tk.Canvas(self.dashboard, height=150, bg=BG, highlightthickness=0)
        self.account_hero.pack(fill="x", pady=(0, 14))
        self.account_hero.bind("<Configure>", lambda _e: self._draw_account_hero())
        # Summary cards
        self.cards = tk.Frame(self.dashboard, bg=BG)
        self.cards.pack(fill="x", pady=(0, 14))
        self.card_values = {}
        for key, label, color in (("income", "EINNAHMEN", GREEN), ("expense", "AUSGABEN", RED), ("net", "SALDOÄNDERUNG", BLUE), ("count", "BUCHUNGEN", GOLD)):
            card = tk.Frame(self.cards, bg=PANEL, highlightbackground=EDGE, highlightthickness=1)
            card.pack(side="left", fill="x", expand=True, padx=(0, 10))
            tk.Label(card, text=label, bg=PANEL, fg=MUTED, font=(FONT, 9, "bold")).pack(anchor="w", padx=16, pady=(12, 3))
            value = tk.Label(card, text="—", bg=PANEL, fg=color, font=(FONT, 18, "bold"))
            value.pack(anchor="w", padx=16, pady=(0, 12))
            self.card_values[key] = value

        row = tk.Frame(self.dashboard, bg=BG)
        row.pack(fill="x", pady=(0, 14))
        self.month_chart = tk.Canvas(row, height=215, bg=PANEL, highlightthickness=1, highlightbackground=EDGE)
        self.month_chart.pack(side="left", fill="both", expand=True, padx=(0, 12))
        self.category_chart = tk.Canvas(row, width=350, height=215, bg=PANEL, highlightthickness=1, highlightbackground=EDGE)
        self.category_chart.pack(side="right", fill="y")
        self.month_chart.bind("<Configure>", lambda _e: self._draw_charts())
        self.category_chart.bind("<Configure>", lambda _e: self._draw_charts())

        controls = tk.Frame(self.dashboard, bg=BG)
        controls.pack(fill="x", pady=(0, 8))
        tk.Label(controls, text="Buchungen", bg=BG, fg=TEXT, font=(FONT, 14, "bold")).pack(side="left")
        self.count_label = tk.Label(controls, text="", bg=BG, fg=MUTED, font=(FONT, 9))
        self.count_label.pack(side="left", padx=10)
        self.search_entry = tk.Entry(controls, textvariable=self.search, bg=PANEL2, fg=TEXT,
                                     insertbackground=TEXT, relief="flat", font=(FONT, 10), width=28)
        self.search_entry.pack(side="right", ipady=8, padx=(8, 0))
        self.search_entry.insert(0, "")
        self.search.trace_add("write", lambda *_: self._refresh())
        tk.Label(controls, text="Suche", bg=BG, fg=MUTED, font=(FONT, 9)).pack(side="right", padx=5)
        self.period_box = ttk.Combobox(controls, textvariable=self.period, state="readonly", width=17,
                                       style="Dark.TCombobox")
        self.period_box.pack(side="right", ipady=3)
        self.period_box.bind("<<ComboboxSelected>>", lambda _e: self._refresh())
        tk.Label(controls, text="Zeitraum", bg=BG, fg=MUTED, font=(FONT, 9)).pack(side="right", padx=(10, 5))

        table_wrap = tk.Frame(self.dashboard, bg=PANEL, highlightbackground=EDGE, highlightthickness=1)
        table_wrap.pack(fill="both", expand=True)
        cols = ("date", "name", "text", "amount", "balance")
        self.table = ttk.Treeview(table_wrap, columns=cols, show="headings")
        headers = (("date", "BUCHUNGSTAG", 110), ("name", "NAME", 230), ("text", "BUCHUNGSTEXT", 300),
                   ("amount", "BETRAG", 130), ("balance", "SALDO", 130))
        for col, label, width in headers:
            self.table.heading(col, text=label, command=lambda column=col: self._sort_by(column))
            minimum = {"date": 95, "name": 135, "text": 160, "amount": 115, "balance": 115}[col]
            self.table.column(col, width=width, minwidth=minimum,
                              stretch=col in ("name", "text"),
                              anchor="e" if col in ("amount", "balance") else "w")
        scroll = ttk.Scrollbar(table_wrap, orient="vertical", command=self.table.yview)
        self.table.configure(yscrollcommand=scroll.set)
        self.table.pack(side="left", fill="both", expand=True, padx=1, pady=1)
        scroll.pack(side="right", fill="y")
        self.table.tag_configure("income", foreground=GREEN)
        self.table.tag_configure("expense", foreground="#ff9aab")
        self.table.bind("<Double-1>", self._show_detail)
        self.table.bind("<Configure>", self._resize_table_columns)

    @staticmethod
    def _draw_logo(canvas):
        canvas.create_oval(4, 4, 50, 50, fill="#17233a", outline="#426084", width=2)
        canvas.create_text(27, 27, text="S", fill=GREEN, font=(FONT, 28, "bold"))
        canvas.create_arc(8, 8, 46, 46, start=215, extent=220, style="arc", outline=BLUE, width=2)

    def _draw_account_hero(self):
        canvas = self.account_hero
        if not canvas.winfo_exists():
            return
        canvas.delete("all")
        w = max(canvas.winfo_width(), 450)
        h = max(canvas.winfo_height(), 140)
        # Horizontal indigo-to-teal wash, echoing the supplied tracker screen.
        left = (25, 28, 62)
        right = (14, 62, 75)
        for x in range(w):
            t = x / max(w - 1, 1)
            rgb = tuple(round(a + (b-a)*t) for a, b in zip(left, right))
            color = "#%02x%02x%02x" % rgb
            canvas.create_line(x, 1, x, h-2, fill=color)
        canvas.create_rectangle(1, 1, w-2, h-2, outline=EDGE, width=1)
        canvas.create_text(28, 27, text="KONTOSTAND AUF EINEN BLICK", anchor="w",
                           fill=BLUE, font=(FONT, 9, "bold"))
        balance = money(self.rows[0]["balance"]) if self.rows else "CSV-Kontoübersicht"
        canvas.create_text(28, 65, text=balance, anchor="w", fill=TEXT,
                           font=(FONT, 24, "bold"))
        canvas.create_text(29, 97, text="Letzter erfasster Kontostand · Buchungen werden nur angezeigt",
                           anchor="w", fill="#b7c7d8", font=(FONT, 9))
        # Subtle account glyph and latest CSV date on the right.
        cx = max(w - 270, 350)
        canvas.create_oval(cx-31, 31, cx+31, 93, outline="#397d83", width=2)
        canvas.create_line(cx-22, 62, cx+22, 62, fill="#397d83", width=2)
        canvas.create_line(cx, 40, cx, 84, fill="#397d83", width=2)
        canvas.create_text(cx+45, 53, text="CSV", anchor="w", fill=TEXT, font=(FONT, 17, "bold"))
        canvas.create_text(cx+45, 78, text="lokale Datei", anchor="w", fill="#b7c7d8", font=(FONT, 9))
        latest = self.rows[0]["date"].strftime("%d.%m.%Y") if self.rows else ""
        canvas.create_text(w-24, 60, text=latest, anchor="e", fill=TEXT, font=(FONT, 14, "bold"))
        canvas.create_text(w-24, 83, text="letzter Buchungstag", anchor="e", fill="#b7c7d8", font=(FONT, 9))

    def open_file(self):
        fallback = os.path.expanduser("~/Downloads/Telegram Desktop")
        if not os.path.isdir(fallback):
            fallback = os.path.expanduser("~/Downloads")
        initial = self.last_directory if self.last_directory and os.path.isdir(self.last_directory) else fallback
        path = filedialog.askopenfilename(title="Sparkasse-CSV auswählen", initialdir=initial,
                                          filetypes=(("CSV-Dateien", "*.csv"), ("Alle Dateien", "*")))
        if path:
            self.load_file(path)

    @staticmethod
    def _settings_path():
        config_home = os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config"))
        return os.path.join(config_home, "sparkasse-viewer", "settings.json")

    def _read_last_directory(self):
        try:
            with open(self._settings_path(), "r", encoding="utf-8") as f:
                directory = json.load(f).get("last_directory", "")
            return directory if isinstance(directory, str) and os.path.isdir(directory) else ""
        except (OSError, ValueError, TypeError):
            return ""

    def _remember_directory(self, path):
        directory = os.path.dirname(os.path.abspath(path))
        try:
            settings_path = self._settings_path()
            os.makedirs(os.path.dirname(settings_path), exist_ok=True)
            with open(settings_path, "w", encoding="utf-8") as f:
                json.dump({"last_directory": directory}, f)
            self.last_directory = directory
        except OSError:
            # Remembering the folder is a convenience; opening the CSV still succeeds
            # when the local config directory is read-only.
            self.last_directory = directory

    def load_file(self, path):
        try:
            rows = read_csv(path)
            if not rows:
                raise ValueError("Es wurden keine Buchungen mit lesbarem Datum und Betrag gefunden.")
        except Exception as exc:
            messagebox.showerror("CSV konnte nicht gelesen werden", str(exc), parent=self)
            return
        self.rows, self.path = rows, path
        self._remember_directory(path)
        self.file_label.config(text=os.path.basename(path), fg=GREEN)
        self.empty.place_forget()
        self.dashboard.pack(fill="both", expand=True)
        years = sorted({r["date"].year for r in rows}, reverse=True)
        months = sorted({r["date"].strftime("%Y-%m") for r in rows}, reverse=True)
        period_options = ["Alle Buchungen"]
        period_options.extend(f"Jahr {year}" for year in years)
        period_options.extend(f"Monat {month[5:7]}.{month[:4]}" for month in months)
        self.period_box["values"] = period_options
        self.period.set("Alle Buchungen")
        self._refresh()

    def _filtered(self):
        period = self.period.get()
        if period.startswith("Jahr "):
            year = int(period.removeprefix("Jahr "))
            return [r for r in self.rows if r["date"].year == year]
        if period.startswith("Monat "):
            month, year = period.removeprefix("Monat ").split(".")
            return [r for r in self.rows if r["date"].strftime("%Y-%m") == f"{year}-{month}"]
        return self.rows

    def _refresh(self):
        rows = self._matching_rows()
        income = sum(r["amount"] for r in rows if r["amount"] > 0)
        expense = -sum(r["amount"] for r in rows if r["amount"] < 0)
        net = income - expense
        self._draw_account_hero()
        self.card_values["income"].config(text=money(income))
        self.card_values["expense"].config(text=money(expense))
        self.card_values["net"].config(text=money(net))
        self.card_values["count"].config(text=f"{len(rows):,}".replace(",", "."))
        self._refresh_table()
        self._draw_charts()

    def _refresh_table(self):
        if not hasattr(self, "table") or not self.rows:
            return
        data = self._matching_rows()
        key_for = {
            "date": lambda r: r["date"],
            "name": lambda r: r["name"].casefold(),
            "text": lambda r: r["text"].casefold(),
            "category": lambda r: r["category"].casefold(),
            "amount": lambda r: r["amount"],
            "balance": lambda r: r["balance"],
        }[self.sort_column]
        data.sort(key=key_for, reverse=self.sort_descending)
        labels = {"date": "BUCHUNGSTAG", "name": "NAME", "text": "BUCHUNGSTEXT",
                  "amount": "BETRAG", "balance": "SALDO"}
        for column, label in labels.items():
            marker = (" ▼" if self.sort_descending else " ▲") if column == self.sort_column else ""
            self.table.heading(column, text=label + marker)
        self.table.delete(*self.table.get_children())
        for r in data[:3000]:
            tag = "income" if r["amount"] >= 0 else "expense"
            self.table.insert("", "end", values=(r["date"].strftime("%d.%m.%Y"), r["name"], r["text"], money(r["amount"]), money(r["balance"])), tags=(tag,))
        self.count_label.config(text=f"{len(data):,} angezeigt".replace(",", "."))

    def _matching_rows(self):
        query = self.search.get().strip().casefold()
        return [r for r in self._filtered()
                if not query or query in " ".join((r["name"], r["text"], r["category"], r["purpose"])).casefold()]

    def _sort_by(self, column: str):
        if column == self.sort_column:
            self.sort_descending = not self.sort_descending
        else:
            self.sort_column = column
            # Names and text start A–Z; dates and numeric values show newest/highest first.
            self.sort_descending = column in ("date", "amount", "balance")
        self._refresh_table()

    def _resize_table_columns(self, event):
        # Keep the numeric/date columns compact and divide remaining width between
        # payee and booking text, so the table uses the available window space.
        available = max(0, event.width - 26)
        fixed = 110 + 130 + 130
        flexible = max(135 + 160, available - fixed)
        name_width = int(flexible * 0.48)
        text_width = flexible - name_width
        self.table.column("date", width=110)
        self.table.column("amount", width=130)
        self.table.column("balance", width=130)
        self.table.column("name", width=max(135, name_width))
        self.table.column("text", width=max(160, text_width))

    def _draw_charts(self):
        if not hasattr(self, "month_chart") or not self.rows:
            return
        rows = self._matching_rows()
        c = self.month_chart
        w, h = max(c.winfo_width(), 420), max(c.winfo_height(), 180)
        c.delete("all")
        c.create_text(18, 18, text="Einnahmen & Ausgaben", anchor="w", fill=TEXT, font=(FONT, 11, "bold"))
        c.create_text(w-18, 18, text=self.period.get(), anchor="e", fill=MUTED, font=(FONT, 9))
        by_month = defaultdict(lambda: [0.0, 0.0])
        for r in rows:
            pair = by_month[r["date"].strftime("%Y-%m")]
            pair[0 if r["amount"] > 0 else 1] += abs(r["amount"])
        keys = sorted(by_month)[-12:]
        if not keys:
            return
        # Keep month labels and the legend on separate rows at the chart bottom.
        left, right, top, bottom = 48, w-18, 48, h-53
        maxv = max(max(by_month[k]) for k in keys) or 1
        for i in range(4):
            y = bottom - (bottom-top)*i/3
            c.create_line(left, y, right, y, fill="#1a3045")
        slot = (right-left)/len(keys)
        bw = max(5, min(14, slot*.25))
        for i, key in enumerate(keys):
            x = left + slot*(i+.5)
            inc, out = by_month[key]
            for dx, value, color in ((-bw*.65, inc, GREEN), (bw*.65, out, RED)):
                y = bottom - (bottom-top)*value/maxv
                c.create_rectangle(x+dx-bw/2, y, x+dx+bw/2, bottom, fill=color, outline="")
            label = dt.datetime.strptime(key, "%Y-%m").strftime("%b %y")
            c.create_text(x, bottom+13, text=label, fill=MUTED, font=(FONT, 8))
        c.create_oval(18, h-19, 26, h-11, fill=GREEN, outline="")
        c.create_text(31, h-15, text="Einnahmen", anchor="w", fill=MUTED, font=(FONT, 8))
        c.create_oval(112, h-19, 120, h-11, fill=RED, outline="")
        c.create_text(125, h-15, text="Ausgaben", anchor="w", fill=MUTED, font=(FONT, 8))

        cat = self.category_chart
        cw, ch = max(cat.winfo_width(), 300), max(cat.winfo_height(), 180)
        cat.delete("all")
        cat.create_text(16, 18, text="Ausgaben nach Kategorie", anchor="w", fill=TEXT, font=(FONT, 11, "bold"))
        totals = defaultdict(float)
        for r in rows:
            if r["amount"] < 0:
                totals[r["category"]] += -r["amount"]
        topcats = sorted(totals.items(), key=lambda kv: kv[1], reverse=True)[:5]
        maxcat = max((v for _, v in topcats), default=1) or 1
        palette = (BLUE, GREEN, GOLD, "#a27bff", RED)
        for i, (name, value) in enumerate(topcats):
            y = 48+i*31
            cat.create_text(16, y, text=(name[:27] + ("…" if len(name)>27 else "")), anchor="w", fill=MUTED, font=(FONT, 8))
            cat.create_rectangle(16, y+10, cw-58, y+20, fill="#172b40", outline="")
            cat.create_rectangle(16, y+10, 16+(cw-74)*value/maxcat, y+20, fill=palette[i], outline="")
            cat.create_text(cw-14, y+15, text=f"{value:.0f} €", anchor="e", fill=TEXT, font=(FONT, 8, "bold"))
        if not topcats:
            cat.create_text(cw/2, ch/2, text="Keine Ausgaben im Zeitraum", fill=MUTED, font=(FONT, 9))

    def _show_detail(self, event):
        item = self.table.identify_row(event.y)
        if not item:
            return
        values = self.table.item(item, "values")
        # Tooltip-free, privacy-conscious detail: double click shows only the selected row.
        row = next((r for r in self._filtered() if r["date"].strftime("%d.%m.%Y") == values[0] and r["name"] == values[1] and money(r["amount"]) == values[4]), None)
        if row and row["purpose"]:
            messagebox.showinfo("Buchungsdetails", row["purpose"], parent=self)


if __name__ == "__main__":
    SparkasseApp().mainloop()
