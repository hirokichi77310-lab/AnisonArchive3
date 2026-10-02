package jp.example;

import java.sql.Connection;
import java.sql.SQLException;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

/**
 * アプリの起動役。
 * 「DBにつなぐ」→「TOPページの画面を作る」→「画面を表示する」の3ステップだけを行う、
 * 一番シンプルな入り口（エントリーポイント）。
 * DBにつなげないときは、原因（どのファイルを見ているか等）をダイアログで知らせて終了する。
 */
public class MainApp {

	public static void main(String[] args) {
		Connection connection;
		try {
			connection = Database.connect();
		} catch (SQLException ex) {
			JOptionPane.showMessageDialog(null, "データベースに接続できません。\n\n" + ex.getMessage(), "起動エラー",
					JOptionPane.ERROR_MESSAGE);
			return;
		}

		JFrame topFrame = TopWindow.build(connection);
		topFrame.setVisible(true);
	}
}