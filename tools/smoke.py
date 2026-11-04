"""Integrasi JAR sebenarnya, oracle CSV stdlib, dan pemeriksaan class Java 17."""

import csv
import hashlib
import io
import json
from pathlib import Path
import random
import subprocess
import tempfile
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "dist" / "recalldesk.jar"


def main():
    checks = []

    def run(*args, expected=0, cwd=None, headless=False):
        command = ["java"] + (["-Djava.awt.headless=true"] if headless else []) + ["-jar", str(JAR), *map(str, args)]
        result = subprocess.run(command, cwd=cwd, capture_output=True, encoding="utf-8", timeout=25)
        assert result.returncode == expected, result.stdout + result.stderr
        return result

    def check(condition, label):
        assert condition, label
        checks.append(label)

    with zipfile.ZipFile(JAR) as jar:
        classes = [name for name in jar.namelist() if name.endswith(".class")]
        assert classes
        for name in classes:
            data = jar.read(name)
            assert data[:4] == bytes.fromhex("cafebabe")
            assert int.from_bytes(data[6:8], "big") == 61, name
        check(True, "Seluruh class menargetkan Java 17 / major 61")

    with tempfile.TemporaryDirectory(prefix="recalldesk-smoke-") as temporary:
        root = Path(temporary)
        data = root / "koleksi 日本.rdb"
        sample = ROOT / "examples" / "kartu-informatika.csv"
        check(run("--version", cwd=root).stdout.strip() == "1.0.0", "Versi JAR")
        check("RecallDesk" in run("--help", cwd=root).stdout, "Bantuan JAR")
        demo = json.loads(run("demo", cwd=root).stdout)
        check(demo["passed"] == 16 and demo["backup_restore"], "Demo 16/16 dari luar source")
        check(json.loads(run("info", "--data", data).stdout)["cards"] == 0, "Pustaka baru kosong")
        imported = json.loads(run("import", "--data", data, "--csv", sample).stdout)
        check(imported["imported"] == 6 and imported["revision"] == 1, "Impor contoh")
        info = json.loads(run("info", "--data", data).stdout)
        check(info["cards"] == 6 and info["due"] == 6 and info["reviews"] == 0, "Persistensi lintas proses")
        before = data.read_bytes()
        run("import", "--data", data, "--csv", sample, expected=2)
        check(data.read_bytes() == before, "Impor duplikat tidak memodifikasi data")
        output = root / "export.csv"
        check(json.loads(run("export", "--data", data, "--csv", output).stdout)["exported"] == 6, "Ekspor seluruh kartu")
        with output.open(encoding="utf-8", newline="") as stream:
            check(len(list(csv.DictReader(stream))) == 6, "Ekspor dibaca parser CSV Python")
        exported = output.read_bytes()
        run("export", "--data", data, "--csv", output, expected=2)
        check(output.read_bytes() == exported, "Output lama tidak ditimpa")
        invalid = root / "bad.csv"
        invalid.write_text('tag,front,back\nX,Q,A\nY,"belum ditutup', encoding="utf-8")
        run("import", "--data", data, "--csv", invalid, expected=2)
        check(data.read_bytes() == before, "CSV rusak tidak mengimpor sebagian")
        invalid.write_bytes(b"\xff\xfe")
        run("import", "--data", data, "--csv", invalid, expected=2)
        check(data.read_bytes() == before, "UTF-8 tidak valid ditolak")
        additional = root / "tambahan.csv"
        additional.write_text("tag,front,back\nTambahan,Siapa yang mengelola memori Java?,Garbage collector\n", encoding="utf-8")
        run("import", "--data", data, "--csv", additional)
        check(json.loads(run("info", "--data", data).stdout)["cards"] == 7, "Commit kedua membuat backup")
        corrupted = bytearray(data.read_bytes()); corrupted[-1] ^= 1; data.write_bytes(corrupted)
        run("info", "--data", data, expected=2)
        check(data.read_bytes() == corrupted, "Data rusak tidak ditimpa otomatis")
        restored = json.loads(run("restore-backup", "--data", data).stdout)
        check(restored == {"restored": True, "cards": 6, "revision": 1}, "Restore generasi sebelumnya")
        check(json.loads(run("info", "--data", data).stdout)["cards"] == 6, "Pustaka hasil restore dapat dibuka")
        gui_data = root / "jangan-dibuat.rdb"
        run("gui", "--sample", "--data", gui_data, expected=2, headless=True)
        check(not gui_data.exists(), "GUI headless ditolak sebelum membuat pustaka")
        for arguments in [("unknown",), ("info", "--csv", output), ("import", "--data", data),
                          ("info", "--data", data, "--data", data), ("gui", "--data")]:
            run(*arguments, expected=2)
        check(True, "Lima sintaks CLI tidak valid ditolak")

        generator = random.Random(20261105)
        alphabet = ' abcd日本café,\"\n\t=0123'
        def normalized(text):
            return unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n").strip())
        for case in range(8):
            rows = []
            for index in range(25):
                rows.append([f"Topik{index % 3}", f"Q{case}/{index} " + "".join(generator.choices(alphabet, k=40)),
                             "A " + "".join(generator.choices(alphabet, k=100))])
            text = io.StringIO(newline="")
            writer = csv.writer(text, lineterminator="\r\n", quoting=csv.QUOTE_ALL)
            writer.writerow(["tag", "front", "back"]); writer.writerows(rows)
            source = root / f"oracle-{case}.csv"; source.write_bytes(("\ufeff" + text.getvalue()).encode("utf-8"))
            library = root / f"oracle-{case}.rdb"; export = root / f"oracle-{case}-out.csv"
            run("import", "--data", library, "--csv", source)
            run("export", "--data", library, "--csv", export)
            with export.open(encoding="utf-8", newline="") as stream:
                actual = list(csv.reader(stream))
            wanted = [["tag", "front", "back"], *[[normalized(cell) for cell in row] for row in rows]]
            assert actual == wanted, case
        print(json.dumps({"cli_checks": len(checks), "csv_oracle_cases": 8, "cards_checked": 200,
                          "class_major_version": 61, "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
