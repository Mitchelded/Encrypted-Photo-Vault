"""Offline Windows desktop UI for EPV1 (Tkinter, no cloud credentials needed)."""
from __future__ import annotations

from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from .codec import encrypt_file, restore_directory, inspect_files


class VaultWindow:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title('Encrypted Photo Vault — EPV1')
        self.root.geometry('700x470')
        self.root.minsize(620, 400)
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.busy = False
        shell = ttk.Frame(self.root, padding=18)
        shell.pack(fill='both', expand=True)
        ttk.Label(shell, text='Encrypted Photo Vault', font=('Segoe UI', 17, 'bold')).pack(anchor='w')
        ttk.Label(shell, text='Фото, видео и любые файлы → зашифрованные PNG для Google Фото').pack(anchor='w', pady=(0, 18))
        prow = ttk.Frame(shell)
        prow.pack(fill='x')
        ttk.Label(prow, text='Пароль:').pack(side='left')
        self.password = ttk.Entry(prow, show='●')
        self.password.pack(side='left', fill='x', expand=True, padx=10)
        self.show_password = tk.BooleanVar()
        ttk.Checkbutton(prow, text='Показать', variable=self.show_password,
                        command=lambda: self.password.config(show='' if self.show_password.get() else '●')).pack(side='right')
        self.buttons: list[ttk.Button] = []
        for text, command in (
            ('Зашифровать файлы', self.choose_files),
            ('Зашифровать папку', self.choose_folder),
            ('Восстановить из PNG', self.choose_restore),
            ('Проверить набор PNG', self.choose_inspect),
        ):
            button = ttk.Button(shell, text=text, command=command)
            button.pack(fill='x', pady=(12, 0) if not self.buttons else (6, 0))
            self.buttons.append(button)
        self.progress = ttk.Progressbar(shell, mode='determinate', maximum=100)
        self.progress.pack(fill='x', pady=(18, 8))
        self.console = tk.Text(shell, height=9, wrap='word', state='disabled')
        self.console.pack(fill='both', expand=True)
        self.status('Локальная обработка. Не удаляйте оригиналы до проверки восстановления.')
        self.root.after(100, self.poll)

    def status(self, text: str) -> None:
        self.console.configure(state='normal')
        self.console.insert('end', text + '\n')
        self.console.see('end')
        self.console.configure(state='disabled')

    def poll(self) -> None:
        try:
            while True:
                typ, value = self.events.get_nowait()
                if typ == 'text':
                    self.status(str(value))
                elif typ == 'progress':
                    n, total = value  # type: ignore[misc]
                    self.progress['value'] = int(100 * n / total)
                elif typ == 'done':
                    self.busy = False
                    for b in self.buttons:
                        b.configure(state='normal')
                    self.progress['value'] = 100
                elif typ == 'error':
                    self.status('ОШИБКА: ' + str(value))
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def start(self, job) -> None:
        if self.busy:
            return
        password = self.password.get()
        if not password:
            messagebox.showwarning('EPV1', 'Сначала введите пароль.')
            return
        self.busy = True
        self.progress['value'] = 0
        for b in self.buttons:
            b.configure(state='disabled')

        def run() -> None:
            try:
                job(password)
            except Exception as exc:
                self.events.put(('error', str(exc)))
            finally:
                self.events.put(('done', None))
        threading.Thread(target=run, daemon=True).start()

    def encrypt(self, files: list[Path]) -> None:
        if not files:
            return
        dest = filedialog.askdirectory(title='Папка для зашифрованных PNG')
        if not dest:
            return
        out = Path(dest).resolve()
        files = [p for p in files if p.resolve() != out and out not in p.resolve().parents]
        def job(password: str) -> None:
            created = 0
            for index, p in enumerate(files):
                self.events.put(('text', f'[{index + 1}/{len(files)}] {p.name}'))
                parts = encrypt_file(p, out, password)
                created += len(parts)
                self.events.put(('progress', (index + 1, len(files))))
            self.events.put(('text', f'Готово. {len(files)} файлов, {created} PNG.'))
        self.start(job)

    def choose_files(self) -> None:
        paths = filedialog.askopenfilenames(title='Выберите фото, видео и другие файлы')
        self.encrypt([Path(p) for p in paths])

    def choose_folder(self) -> None:
        folder = filedialog.askdirectory(title='Папка с исходными файлами')
        if folder:
            self.encrypt([p for p in Path(folder).rglob('*') if p.is_file()])

    def choose_restore(self) -> None:
        folder = filedialog.askdirectory(title='Папка с загруженными PNG')
        if not folder:
            return
        out = filedialog.askdirectory(title='Куда восстановить оригиналы')
        if not out:
            return
        def job(password: str) -> None:
            results = restore_directory(folder, out, password)
            self.events.put(('text', 'Восстановлены файлы:'))
            for result in results:
                self.events.put(('text', '  ' + str(result)))
        self.start(job)

    def choose_inspect(self) -> None:
        folder = filedialog.askdirectory(title='Папка с PNG для проверки')
        if not folder:
            return
        def job(_password: str) -> None:
            groups = inspect_files(Path(folder).rglob('*.png'))
            for fid, chunks in groups.items():
                count = chunks[0][1].part_count
                indexes = {h.part_index for _, h in chunks}
                self.events.put(('text', f'{fid}: {len(indexes)}/{count} частей'))
            if not groups:
                self.events.put(('text', 'EPV1-файлы не найдены.'))
        self.start(job)

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    VaultWindow().run()


if __name__ == '__main__':
    main()
