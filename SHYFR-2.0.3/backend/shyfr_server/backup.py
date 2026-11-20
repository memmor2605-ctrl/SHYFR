"""Kopia zapasowa i odtwarzanie bazy serwera SHYFR 2.0 (SQLite).

  python -m shyfr_server.backup backup  <baza> <plik_kopii>      # spójna kopia "na gorąco" (API backup SQLite), z weryfikacją
  python -m shyfr_server.backup verify  <plik_kopii>             # integrity_check + sprawdzenie schematu
  python -m shyfr_server.backup restore <plik_kopii> <baza>      # odtworzenie; odmawia nadpisania istniejącej bazy bez --force

Kopia zawiera WSZYSTKO, co serwer wie o tożsamościach (konta, urządzenia, klucze publiczne, kolejki), więc musi być chroniona jak baza produkcyjna
(szyfrowanie dysku/archiwum, ograniczony dostęp). Serwer nie przechowuje kluczy prywatnych użytkowników, więc odtworzenie bazy nie "wymyśla" tożsamości na nowo:
konta, identyfikatory urządzeń i klucze publiczne wracają bez zmian. Po odtworzeniu serwer należy zatrzymać na czas operacji.
"""
import os, sqlite3, sys, tempfile

REQUIRED_TABLES = {"accounts", "devices", "sessions", "signed_prekeys", "one_time_prekeys", "messages", "blocks", "schema_migrations"}


class BackupError(Exception): pass


def verify(path):
    """Zwraca listę zastosowanych wersji migracji; rzuca BackupError, gdy plik nie jest poprawną bazą SHYFR."""
    if not os.path.isfile(path): raise BackupError("brak pliku: " + path)
    try:
        c = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    except sqlite3.Error as e:
        raise BackupError("nie można otworzyć: %s" % e)
    try:
        try:
            r = c.execute("PRAGMA integrity_check").fetchall()
            if r != [("ok",)]: raise BackupError("integrity_check: %r" % (r[:3],))
            if c.execute("PRAGMA foreign_key_check").fetchall(): raise BackupError("foreign_key_check wykrył niespójność")
            tables = {x[0] for x in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not REQUIRED_TABLES <= tables: raise BackupError("brak tabel: %s" % sorted(REQUIRED_TABLES - tables))
            return [x[0] for x in c.execute("SELECT version FROM schema_migrations ORDER BY version")]
        except sqlite3.DatabaseError as e:
            raise BackupError("plik nie jest poprawną bazą: %s" % e)
    finally:
        c.close()


def backup(db_path, dest):
    if not os.path.isfile(db_path): raise BackupError("brak bazy: " + db_path)
    if os.path.exists(dest): raise BackupError("plik docelowy już istnieje: " + dest)
    d = os.path.dirname(os.path.abspath(dest)); os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, suffix=".partial"); os.close(fd)
    src = sqlite3.connect(db_path, timeout=30); dst = sqlite3.connect(tmp)
    try:
        src.backup(dst)                          # spójny obraz, także przy trwających zapisach
    finally:
        dst.close(); src.close()
    try:
        versions = verify(tmp)
    except BackupError:
        os.remove(tmp); raise
    os.chmod(tmp, 0o600); os.replace(tmp, dest)  # kopia pojawia się dopiero po weryfikacji
    return versions


def restore(backup_path, db_path, force=False):
    versions = verify(backup_path)
    if os.path.exists(db_path) and not force: raise BackupError("baza docelowa istnieje; użyj --force (poprzednia zostanie zachowana jako .before-restore)")
    d = os.path.dirname(os.path.abspath(db_path)); os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, suffix=".restore"); os.close(fd)
    src = sqlite3.connect("file:%s?mode=ro" % backup_path, uri=True); dst = sqlite3.connect(tmp)
    try:
        src.backup(dst)
    finally:
        dst.close(); src.close()
    try:
        verify(tmp)
    except BackupError:
        os.remove(tmp); raise
    if os.path.exists(db_path):
        os.replace(db_path, db_path + ".before-restore")
        for ext in ("-wal", "-shm"):
            if os.path.exists(db_path + ext): os.remove(db_path + ext)
    os.chmod(tmp, 0o600); os.replace(tmp, db_path)
    return versions


def main(argv):
    try:
        if len(argv) >= 3 and argv[0] == "backup": print("kopia gotowa, migracje:", backup(argv[1], argv[2]))
        elif len(argv) == 2 and argv[0] == "verify": print("kopia poprawna, migracje:", verify(argv[1]))
        elif len(argv) >= 3 and argv[0] == "restore": print("odtworzono, migracje:", restore(argv[1], argv[2], "--force" in argv[3:]))
        else: print(__doc__); return 2
    except BackupError as e:
        print("BŁĄD:", e, file=sys.stderr); return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
