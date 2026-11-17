# Android APK Resource and SQLCipher Database Extraction

This guide documents the static-only workflow used to unpack `SeagullAssistant721.apk`, inspect its resources, identify its encrypted database, and export its data. Do this only for applications and files you own or are authorized to analyze.

## Safety boundary

- Do not install, launch, or execute the APK.
- Work on copies of the APK and database.
- Treat extracted credentials, API identifiers, and user data as sensitive.
- Keep the work offline unless you intentionally install a required tool.

## Findings from this APK

| Item | Finding |
| --- | --- |
| Package | `com.galasoft.ces_crib` |
| Bundled database | `assets/ces_qa.db` |
| Database type | SQLCipher-encrypted SQLite |
| Native library | `lib/*/libsqlcipher.so` |
| Exports | `CES`, `CBT`, `QUESTIONS`, and `ANSWERS` |

## 1. Install the tools

These instructions use macOS and Homebrew.

```bash
brew install apktool jadx sqlcipher
command -v apktool
command -v jadx
command -v sqlcipher
```

## 2. Inventory the APK

```bash
APK="$HOME/Downloads/SeagullAssistant721.apk"
OUT="$PWD/apk-inspection"
mkdir -p "$OUT"

file "$APK"
unzip -l "$APK"
```

The inventory revealed `assets/ces_qa.db`, `classes.dex`, standard Android resources, and SQLCipher libraries.

## 3. Decode the manifest and resources

```bash
apktool d -f "$APK" -o "$OUT/apktool-decoded"
```

Key outputs:

- `$OUT/apktool-decoded/AndroidManifest.xml` — decoded Android manifest.
- `$OUT/apktool-decoded/res/` — layouts, strings, drawables, themes, and XML resources.
- `$OUT/apktool-decoded/assets/` — bundled app data, including `ces_qa.db`.
- `$OUT/apktool-decoded/smali/` — Dalvik bytecode in smali form.

For Java-like source output, optionally use:

```bash
jadx -d "$OUT/jadx-source" "$APK"
```

## 4. Identify the database format

```bash
DB="$OUT/apktool-decoded/assets/ces_qa.db"
file "$DB"
sqlite3 "$DB" ".tables"
xxd -l 128 "$DB"
python3 -c "import math,collections,sys; d=open(sys.argv[1],'rb').read(); n=len(d); h=-sum((v/n)*math.log2(v/n) for v in collections.Counter(d).values()); print(h)" "$DB"
```

This file does not contain the ordinary `SQLite format 3` header. `sqlite3` reports that it is not a database, and its near-maximum entropy indicates encryption. The APK contains `libsqlcipher.so`, identifying SQLCipher as the format.

## 5. Locate the database access code

```bash
rg -n -i "ces_qa|sqlcipher|SQLiteOpenHelper|getWritableDatabase" "$OUT/apktool-decoded/smali"
sed -n '1,180p' "$OUT/apktool-decoded/smali/c/a/a/a.smali"
```

The class `com/galasoft/ces_crib/j` opens `ces_qa.db` using `net.sqlcipher.database.SQLiteOpenHelper`. It gets the passphrase by calling `c/a/a/a.b(context)`.

## 6. Reconstruct the passphrase derivation

The helper reads this bundled string resource:

```text
ca-app-pub-5289762358451835/5528817307
```

It takes the substring between the relevant hyphen and slash, producing this SQLCipher passphrase:

```text
5289762358451835
```

Reproduce that derivation:

```bash
printf "%s\n" "ca-app-pub-5289762358451835/5528817307" | awk -F"[-/]" '{print $4}'
```

Do not assume other apps use the same pattern. Other apps may derive keys through Android Keystore, native code, user input, or a remote service.

## 7. Verify the encrypted database

```bash
sqlcipher "$DB" \
  "PRAGMA key='5289762358451835';" \
  "SELECT name, sql FROM sqlite_master WHERE type='table';"
```

Expected tables:

| Table | Purpose |
| --- | --- |
| `CES` | Question number/text, four answer choices, and correctness flags |
| `CBT` | CBT identifiers, names, and versions |
| `QUESTIONS` | CBT identifier and question text |
| `ANSWERS` | Answer identifier and answer text |

Count the data:

```bash
sqlcipher "$DB" "PRAGMA key='5289762358451835';
SELECT 'CES', count(*) FROM CES
UNION ALL SELECT 'CBT', count(*) FROM CBT
UNION ALL SELECT 'QUESTIONS', count(*) FROM QUESTIONS
UNION ALL SELECT 'ANSWERS', count(*) FROM ANSWERS;"
```

Results from this APK:

- `CES`: 10,477
- `CBT`: 462
- `QUESTIONS`: 27,678
- `ANSWERS`: 27,678

## 8. Export each table to CSV

```bash
cat > "$OUT/export.sql" <<'SQL'
PRAGMA key='5289762358451835';
.headers on
.mode csv
.once ces.csv
SELECT * FROM CES ORDER BY _id;
.once cbt.csv
SELECT * FROM CBT ORDER BY _id;
.once questions.csv
SELECT * FROM QUESTIONS ORDER BY _id;
.once answers.csv
SELECT * FROM ANSWERS ORDER BY _id;
SQL

(cd "$OUT" && sqlcipher "apktool-decoded/assets/ces_qa.db" < export.sql)
```

The data is now available as `ces.csv`, `cbt.csv`, `questions.csv`, and `answers.csv`. Read them with a CSV-aware tool: some fields can contain embedded newlines, so `wc -l` does not equal the record count.

## 9. Validate the exports

```bash
ls -lh "$OUT"/*.csv
shasum -a 256 "$OUT"/*.csv
head -n 3 "$OUT/ces.csv"
```

Expected sizes for this run were approximately: `ces.csv` 3.3 MB, `cbt.csv` 21 KB, `questions.csv` 3.6 MB, and `answers.csv` 5.3 MB.

## Troubleshooting

| Problem | Resolution |
| --- | --- |
| `sqlite3` says “file is not a database” | Use `sqlcipher`, then run `PRAGMA key=...` before querying. |
| SQLCipher says “file is encrypted or is not a database” | Verify the passphrase and database copy. For an older database, try `PRAGMA cipher_compatibility=3;` before `PRAGMA key=...`. |
| JADX cannot create its configuration | Use a writable configuration location, or review Apktool’s smali output. |
| CSV appears to have too many lines | Parse it as CSV; quoted fields can have embedded newlines. |

The original APK and encrypted database are unchanged. All readable data is contained in the CSV exports.
