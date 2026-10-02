-- アニ伝アーカイブ：空のデータベース（test.db）を作るためのテーブル定義
-- 実際のデータ（作品名・曲・CD情報）は、著作権に配慮してこのリポジトリには含めていません。
-- 下記を実行すれば、空のtest.dbを作れます（例：DB Browser for SQLite や、sqlite3コマンドで実行）。

CREATE TABLE works (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    作品名 TEXT,
    放映開始日 TEXT,
    放映終了日 TEXT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE songData (
    作品名 TEXT,
    放映開始日 TEXT,
    放映終了日 TEXT,
    区分_OP1等 TEXT,
    曲名 TEXT,
    作詞者 TEXT,
    作曲者 TEXT,
    編曲者 TEXT,
    歌唱者 TEXT,
    備考 TEXT,
    work_id INTEGER
);

CREATE TABLE cdData (
    作品名 TEXT,
    放映開始日 TEXT,
    放映終了日 TEXT,
    CDタイトル TEXT,
    収録曲 TEXT,
    購入URL TEXT,
    発売会社 TEXT,
    品番 TEXT,
    備考 TEXT,
    work_id INTEGER
);
