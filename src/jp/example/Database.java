package jp.example;

import java.io.File;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * データベース（test.db）への接続だけを担当するクラス。
 * 「どのファイルに、どうやってつなぐか」をここに1か所だけ書いておくことで、
 * 接続方法を変えたくなったとき（例：ファイル名を変える等）に、ここだけ直せばよくなる。
 *
 * test.dbは、次の順に探す（最初に見つかったものを使う）。
 *  ① 今の作業フォルダ（Eclipseで実行するときは、プロジェクトのフォルダ）
 *  ② アプリ本体（.jar）の場所を基準にした場所（exeにしたときに、exeと同じフォルダに置けるようにするため）
 *
 * 注意：SQLiteは、指定したファイルが無いと「空のデータベース」を黙って作ってしまい、
 * あとで「no such table: works」という分かりにくいエラーになる。
 * そこで接続の前に、ファイルの有無とworksテーブルの有無を確認し、
 * 問題があれば「どこのファイルを見ているか」を含めたメッセージで知らせる。
 */
public class Database {

	private static final String DB_FILE = "test.db";

	/** SQLiteのtest.dbに接続して、その接続（Connection）を返す。 */
	public static Connection connect() throws SQLException {
		File file = findDbFile();
		if (file == null) {
			StringBuilder sb = new StringBuilder("データベースファイル（" + DB_FILE + "）が見つかりません。次の場所を探しました：");
			for (File c : candidates()) {
				sb.append("\n・").append(c.getAbsolutePath());
			}
			throw new SQLException(sb.toString());
		}
		String where = file.getAbsolutePath();

		// SQLiteのドライバー（sqlite-jdbcのjar）を、はっきりと読み込んでおく。
		// jarがビルド・パスから外れている等の理由で読み込めないときは、
		// 「No suitable driver found」という分かりにくいエラーの代わりに、理由が分かるメッセージを出す。
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			throw new SQLException("SQLiteのドライバー（sqlite-jdbcのjarファイル）が、プロジェクトに見つかりません。\n"
					+ "プロジェクトを右クリック→「ビルド・パス」→「ビルド・パスの構成」→「ライブラリー」で、\n"
					+ "sqlite-jdbc-…jar が赤い印なしで入っているか確認してください。");
		}

		Connection conn;
		try {
			conn = DriverManager.getConnection("jdbc:sqlite:" + where);
		} catch (SQLException e) {
			if (e.getMessage() != null && e.getMessage().contains("No suitable driver")) {
				throw new SQLException("SQLiteのドライバー（sqlite-jdbcのjarファイル）が、プロジェクトに見つかりません。\n場所：" + where + "\n"
						+ "プロジェクトを右クリック→「ビルド・パス」→「ビルド・パスの構成」→「ライブラリー」で、\n"
						+ "sqlite-jdbc-…jar が赤い印なしで入っているか確認してください。", e);
			}
			throw e;
		}

		boolean hasWorks;
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='works'")) {
			hasWorks = rs.next();
		}
		if (!hasWorks) {
			conn.close();
			throw new SQLException("このデータベースには works テーブルがありません。\n場所：" + where + "\nサイズ：" + file.length()
					+ " バイト（データ入りのtest.dbは約1,000,000バイトです）\n正しいtest.dbに入れ替えてください");
		}
		return conn;
	}

	/** test.dbが入っているフォルダ（top.htmlなどの書き出し先にも使う）。test.dbが見つからなければ、今の作業フォルダ。 */
	public static File dataFolder() {
		File db = findDbFile();
		return db != null ? db.getParentFile() : new File("").getAbsoluteFile();
	}

	/** test.dbを探して、最初に見つかったファイルを返す（無ければnull）。 */
	static File findDbFile() {
		for (File c : candidates()) {
			if (c.isFile()) {
				return c;
			}
		}
		return null;
	}

	/** test.dbが置かれているかもしれない場所を、探す順に並べたもの。 */
	private static List<File> candidates() {
		List<File> list = new ArrayList<>();
		list.add(new File(DB_FILE).getAbsoluteFile()); // ① 今の作業フォルダ

		File location = appLocation();
		if (location != null) {
			File parent = location.getParentFile();
			if (location.isFile() && parent != null) {
				// .jarの場合：jarと同じフォルダ、その1つ上（jpackageのexeでは、app\ の1つ上がexeのフォルダ）
				list.add(new File(parent, DB_FILE));
				if (parent.getParentFile() != null) {
					list.add(new File(parent.getParentFile(), DB_FILE));
				}
			} else if (parent != null) {
				// フォルダ（Eclipseのbin）の場合：その1つ上（プロジェクトのフォルダ）
				list.add(new File(parent, DB_FILE));
			}
		}
		return list;
	}

	/** このアプリのクラスが入っている場所（.jarファイル、またはクラスのフォルダ）。分からなければnull。 */
	private static File appLocation() {
		try {
			return new File(Database.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsoluteFile();
		} catch (URISyntaxException | RuntimeException e) {
			return null;
		}
	}
}
