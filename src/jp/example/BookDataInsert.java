package jp.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 資料（本の1974年のページ）から読み取ったデータを、DB（test.db）に追加するための道具。
 *
 * 使い方：このクラスを右クリック →「実行」→「Javaアプリケーション」で、1回実行する。
 * ・同じ作品名・開始日の作品がすでにDBにあれば、その作品は追加せずに飛ばす（2回実行しても増えない）。
 * ・以前の版のデータ追加で入れてしまった誤り（歌手名・放映期間）があれば、最初に自動で訂正する。
 * データの出どころ：目次ページ（p.98）と、各作品のページ（p.99〜100）。
 *   OCRの結果を、人が元の画像と見比べて確認したもの。
 */
public class BookDataInsert {

	public static void main(String[] args) throws SQLException {
		try (Connection c = Database.connect()) {

			// 先に、以前の版で入れた誤りを訂正する（先に直すことで、下の「すでにあるか」の判定が正しく働く）
			fixEarlierMistakes(c);

		// ===== アルプスの少女ハイジ =====
		// 曲：{ 区分, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考 }
		String[][] heidiSongs = {
			{ "OP", "おしえて", "岸田衿子", "渡辺岳夫", "松山祐士", "伊集加代子／ネリー・シュワルツ", "" },
			{ "ED", "まっててごらん", "岸田衿子", "渡辺岳夫", "松山祐士", "大杉久美子／ネリー・シュワルツ", "" },
			{ "挿入歌", "ユキとわたし", "岸田衿子", "渡辺岳夫", "松山祐士", "大杉久美子", "OCRの座標から行を特定（歌手）" },
			{ "挿入歌", "夕方の歌", "岸田衿子", "渡辺岳夫", "松山祐士", "大杉久美子", "OCRの座標から行を特定（歌手）" },
			{ "挿入歌", "アルムの子守唄", "岸田衿子", "渡辺岳夫", "松山祐士", "ネリー・シュワルツ", "OCRの座標から行を特定（歌手）" },
			{ "挿入歌", "ペーターとわたし", "岸田衿子", "渡辺岳夫", "松山祐士", "大杉久美子", "OCRの座標から行を特定（歌手）" }
		};
		// 音源：{ タイトル, 収録曲, 発売会社, 品番, 備考 }
		String[][] heidiCds = {
			{ "アルプスの少女ハイジ（ソノシート）", "1,2　※TVマンガシリーズ　ドラマ「こんにちはハイジ」を収録", "AS", "APM-4567", "1974.3発売" },
			{ "アルプスの少女ハイジ（ソノシート）", "1～4　※ドラマを収録", "AS", "APW-9504", "発売日不明" },
			{ "アルプスの少女ハイジ（EP）", "1,2", "Co", "SCS-222", "1974.2発売" },
			{ "★アルプスの少女ハイジ★", "1～6　※B面はドラマ", "Co", "KKS-4098", "1974.5発売" },
			{ "★アルプスの少女ハイジ★", "1～6　※KKS-4098の再版", "Co", "CS-7043", "1977.11発売" },
			{ "テレビオリジナルBGMコレクション／アルプスの少女ハイジ", "1,2のTVサイズを収録", "Co", "CX-7032", "1981.8発売" },
			{ "管弦楽と室内楽による組曲　アルプスの少女ハイジ", "", "Co", "CX-7048-ND", "1982.3発売" },
			{ "カセット絵本1200アポッコ22　アルプスの少女ハイジ", "1～6", "Ap", "APK-22", "1979.11発売" },
			{ "アルプスの少女ハイジ（絵本付カセット）", "1～6　※全曲オリジナルと思われる（セブンシーズ・レーベル）", "Kg", "7P-4", "1980.7発売・現物未確認" },
			{ "カセット絵本1200アポッコ36　アルプスの少女ハイジ1", "1　※ドラマ「アルムの山はバラ色にもえて」を収録", "Ap", "APS2036", "1982.11発売" },
			{ "カセット絵本1200アポッコ37　アルプスの少女ハイジ2", "2　※ドラマ「クララがあるけた！」を収録", "Ap", "APS2037", "1982.11発売" }
		};
		addWork(c, "アルプスの少女ハイジ", "1974/01/06", "1974/12/29", heidiSongs, heidiCds);

		// ===== 魔女っ子メグちゃん =====
		// 曲：{ 区分, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考 }
		String[][] meguSongs = {
			{ "OP", "魔女っ子メグちゃん", "千家和也", "渡辺岳夫", "松山祐士", "前川陽子", "" },
			{ "ED", "ひとりぼっちのメグ", "伊丹亮一郎", "渡辺岳夫", "松山祐士", "前川陽子", "" }
		};
		// 音源：{ タイトル, 収録曲, 発売会社, 品番, 備考 }
		String[][] meguCds = {
			{ "魔女っ子メグちゃん（ソノシート）", "1,2　※TVマンガシリーズ　ドラマ「メグちゃんの魔法合戦」を収録", "AS", "APM-4569", "1974.4発売" },
			{ "魔女っ子メグちゃん（EP）", "1,2", "Co", "SCS-225", "1974.4発売" },
			{ "アニメ・サウンド・メモリアル　魔女っ子メグちゃん", "1,2　※再録音BGMを収録", "Co", "CX-7191", "1984.11発売" }
		};
		addWork(c, "魔女っ子メグちゃん", "1974/04/01", "1975/09/29", meguSongs, meguCds);

		// ===== ダメおやじ =====
		// 曲：{ 区分, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考 }
		String[][] dameSongs = {
			{ "OP", "ダメおやじの唄", "すみあきくん", "すみあきくん", "---", "大泉滉／雷門ケン太", "" },
			{ "ED", "ダメおやじ（BAD-FATHER）「愛のテーマ」", "郷伍郎", "郷伍郎", "---", "サカモト児童合唱団／ペンあんどペンシル", "" }
		};
		// 音源：{ タイトル, 収録曲, 発売会社, 品番, 備考 }
		String[][] dameCds = {
			{ "ダメおやじ（EP）", "1,2　※OPはショートバージョンとロングバージョンがあり、テイクが異なる", "Kg", "TV(H)-17", "1974.5発売" }
		};
		addWork(c, "ダメおやじ", "1974/04/02", "1974/10/09", dameSongs, dameCds);

		// ===== 小さなバイキングビッケ =====
		// 曲：{ 区分, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考 }
		String[][] vickeSongs = {
			{ "OP", "ビッケは小さなバイキング", "丘克美", "宇野誠一郎", "宇野誠一郎", "栗葉子とザ・バイキングス", "歌手表記は資料より判読・要確認" },
			{ "ED", "ちっちゃなビッケの歌", "高垢葵", "宇野誠一郎", "宇野誠一郎", "栗葉子とザ・バイキングス", "歌手表記は資料より判読・要確認" },
			{ "ED", "チッチャなビッケと大きな父さん", "雨宮雄児", "宇野誠一郎", "宇野誠一郎", "デュカル・エコー", "歌手表記は資料より判読・要確認" },
			{ "ED", "フラーケ旗の歌", "雨宮雄児", "宇野誠一郎", "宇野誠一郎", "デュカル・エコー", "歌手表記は資料より判読・要確認" }
		};
		// 音源：{ タイトル, 収録曲, 発売会社, 品番, 備考 }
		String[][] vickeCds = {
			{ "小さなバイキングビッケ（ソノシート）", "1,2　※TVマンガシリーズ　ドラマ「小さなバイキングの誕生」を収録", "AS", "APM-4575", "1974.6発売" },
			{ "小さなバイキングビッケ（EP）", "1,2", "Th", "DT-1016", "1974.5発売" },
			{ "小さなバイキングビッケ（EP）", "1～4　※3,4は東宝レコード発行の目録ではそれぞれ「私はチッチ」「フラーケ旗」となっている。EDは2～4の3曲を毎週交代で使用", "Th", "DT-5002", "1974.7発売" }
		};
		addWork(c, "小さなバイキングビッケ", "1974/04/03", "1975/09/24", vickeSongs, vickeCds);

			System.out.println("完了しました。MainAppを起動して、検索で確認してください。");
		}
	}

	/** 作品1件（作品＋その曲＋その音源）を追加する。すでにあれば何もしない。 */
	private static void addWork(Connection c, String title, String start, String end, String[][] songs, String[][] cds)
			throws SQLException {
		if (workExists(c, title, start)) {
			System.out.println("飛ばしました（すでにあります）：" + title);
			return;
		}

		// 作品（works）を追加して、自動でついた番号（id）を受け取る
		int workId;
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO works (作品名, 放映開始日, 放映終了日) VALUES (?, ?, ?)")) {
			ps.setString(1, title);
			ps.setString(2, start);
			ps.setString(3, end);
			ps.execute();
		}
		try (PreparedStatement ps = c.prepareStatement("SELECT last_insert_rowid()"); ResultSet rs = ps.executeQuery()) {
			rs.next();
			workId = rs.getInt(1);
		}

		// 主題歌（songData）
		for (String[] s : songs) {
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO songData (作品名, 放映開始日, 放映終了日, work_id, 区分_OP1等, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				ps.setString(1, title);
				ps.setString(2, start);
				ps.setString(3, end);
				ps.setInt(4, workId);
				for (int i = 0; i < 7; i++) {
					ps.setString(5 + i, s[i]);
				}
				ps.execute();
			}
		}

		// 音源（cdData）。購入URLは空のまま（Amazonボタンはタイトルから検索URLを作る）
		for (String[] d : cds) {
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO cdData (作品名, 放映開始日, 放映終了日, work_id, CDタイトル, 収録曲, 購入URL, 発売会社, 品番, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				ps.setString(1, title);
				ps.setString(2, start);
				ps.setString(3, end);
				ps.setInt(4, workId);
				ps.setString(5, d[0]);
				ps.setString(6, d[1]);
				ps.setString(7, "");
				ps.setString(8, d[2]);
				ps.setString(9, d[3]);
				ps.setString(10, d[4]);
				ps.execute();
			}
		}
		System.out.println("追加しました：" + title + "（曲 " + songs.length + "件、音源 " + cds.length + "件）");
	}

	private static boolean workExists(Connection c, String title, String start) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM works WHERE 作品名 = ? AND 放映開始日 = ?")) {
			ps.setString(1, title);
			ps.setString(2, start);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}

	/**
	 * 以前の版のデータ追加コードで入れた誤りを訂正する。
	 * 該当するデータが無ければ、何も変わらない（新しく追加した分は、最初から正しい値なので変わらない）。
	 */
	private static void fixEarlierMistakes(Connection c) throws SQLException {
		// アルプスの少女ハイジ：歌手の対応（資料の表を見て確認済み）
		fixSinger(c, "ユキとわたし", "大杉久美子");
		fixSinger(c, "夕方の歌", "大杉久美子");
		fixSinger(c, "アルムの子守唄", "ネリー・シュワルツ");
		fixSinger(c, "ペーターとわたし", "大杉久美子");

		// 放映期間：目次ページ（p.98）で確認した日付
		fixPeriod(c, "ダメおやじ", "1974/04/02", "1974/10/09");
		fixPeriod(c, "小さなバイキングビッケ", "1974/04/03", "1975/09/24");
	}

	private static void fixSinger(Connection c, String songTitle, String singer) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE songData SET 歌唱者 = ?, 備考 = '' WHERE 作品名 = 'アルプスの少女ハイジ' AND 曲名 = ?")) {
			ps.setString(1, singer);
			ps.setString(2, songTitle);
			ps.execute();
		}
	}

	/** works・songData・cdDataの3か所にある、放映期間をそろえて直す。 */
	private static void fixPeriod(Connection c, String title, String start, String end) throws SQLException {
		for (String table : new String[] { "works", "songData", "cdData" }) {
			try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET 放映開始日 = ?, 放映終了日 = ? WHERE 作品名 = ?")) {
				ps.setString(1, start);
				ps.setString(2, end);
				ps.setString(3, title);
				ps.execute();
			}
		}
	}
}
