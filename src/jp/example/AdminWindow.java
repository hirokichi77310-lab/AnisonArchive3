package jp.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * 管理者向けの画面。
 * ログイン → データ一覧（編集・削除）→ 削除済み一覧（復活・完全削除）
 * → 新規追加 → HTML版（top.html）の書き出し、という流れをまとめている。
 */
public class AdminWindow {


	/**
	 * 「管理者ログイン」ボタンが押されたときに呼ばれる。
	 * まだ管理者アカウントが設定されていなければ（初回起動など）、先に初期設定の画面を開く。
	 */
	public static void openLogin(Connection connection, JFrame topFrame) {
		if (!AdminAuth.hasAccount()) {
			openAccountSetup(connection, topFrame, null);
			return;
		}

		JFrame loginFrame = new JFrame("管理者ログイン");
		loginFrame.setSize(300, 150);
		loginFrame.setLayout(new java.awt.GridLayout(3, 2, 5, 5));

		JTextField userField = new JTextField(10);
		JPasswordField passField = new JPasswordField(10);
		JButton loginButton = new JButton("ログイン");

		loginFrame.add(new JLabel("ユーザー名："));
		loginFrame.add(userField);
		loginFrame.add(new JLabel("パスワード："));
		loginFrame.add(passField);
		loginFrame.add(new JLabel("")); // 空っぽの場所
		loginFrame.add(loginButton);

		loginButton.addActionListener(lv -> {
			String user = userField.getText();
			String pass = new String(passField.getPassword());

			if (AdminAuth.authenticate(user, pass)) {
				loginFrame.dispose();
				openAdminFrame(connection);
			} else {
				JOptionPane.showMessageDialog(loginFrame, "ユーザー名またはパスワードが違います");
			}
		});
		// パスワード欄でEnterキーを押しても、ログインボタンと同じ動きにする
		passField.addActionListener(pv -> loginButton.doClick());

		loginFrame.setVisible(true);
	}

	/**
	 * 管理者アカウントの設定画面。
	 * 初回起動時（まだ何も設定されていないとき）と、「パスワードを変更」から開いたとき（今のパスワードの確認つき）の、
	 * 両方で使う。requireCurrentPasswordCheckがnullなら初回、そうでなければ変更用。
	 */
	private static void openAccountSetup(Connection connection, JFrame topFrame, Runnable requireCurrentPasswordCheck) {
		boolean firstTime = requireCurrentPasswordCheck == null;
		JFrame setupFrame = new JFrame(firstTime ? "管理者アカウントの初期設定" : "パスワードを変更");
		setupFrame.setSize(380, firstTime ? 260 : 300);
		setupFrame.setLayout(new BoxLayout(setupFrame.getContentPane(), BoxLayout.Y_AXIS));

		JPanel msgPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		msgPanel.add(new JLabel(firstTime ? "<html>最初に、管理者のユーザー名とパスワードを<br>決めてください。あとからでも変更できます。</html>"
				: "<html>新しいユーザー名・パスワードを入力してください。</html>"));
		setupFrame.add(msgPanel);

		JTextField currentPassField0 = null;
		if (!firstTime) {
			JPanel curRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
			curRow.add(new JLabel("今のパスワード："));
			JPasswordField curPass = new JPasswordField(14);
			curRow.add(curPass);
			setupFrame.add(curRow);
			currentPassField0 = curPass; // 型合わせ用（下のラムダで実体を使う）
		}
		final JPasswordField currentPassField = (JPasswordField) currentPassField0;

		JPanel userRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		userRow.add(new JLabel("　ユーザー名："));
		JTextField userField = new JTextField(14);
		userField.setText(firstTime ? "admin" : "");
		userRow.add(userField);
		setupFrame.add(userRow);

		JPanel pass1Row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		pass1Row.add(new JLabel("新しいパスワード："));
		JPasswordField pass1 = new JPasswordField(14);
		pass1Row.add(pass1);
		setupFrame.add(pass1Row);

		JPanel pass2Row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		pass2Row.add(new JLabel("パスワード（確認）："));
		JPasswordField pass2 = new JPasswordField(14);
		pass2Row.add(pass2);
		setupFrame.add(pass2Row);

		JButton okButton = new JButton(firstTime ? "この内容で設定する" : "変更する");
		JPanel okRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
		okRow.add(okButton);
		setupFrame.add(okRow);

		okButton.addActionListener(ev -> {
			if (!firstTime) {
				String cur = new String(currentPassField.getPassword());
				// 変更前は、必ず「今のユーザー名」でなく、とにかく今のパスワードが合っているかだけ確認する
				// （ユーザー名を忘れていても、パスワードさえ分かれば変更できるようにするため、保存済みユーザー名を使う）
				if (!AdminAuth.authenticate(AdminAuth.currentUsernameOrEmpty(), cur)) {
					JOptionPane.showMessageDialog(setupFrame, "今のパスワードが違います。");
					return;
				}
			}
			String u = userField.getText().trim();
			String p1 = new String(pass1.getPassword());
			String p2 = new String(pass2.getPassword());
			if (u.isEmpty() || p1.isEmpty()) {
				JOptionPane.showMessageDialog(setupFrame, "ユーザー名とパスワードを入力してください。");
				return;
			}
			if (!p1.equals(p2)) {
				JOptionPane.showMessageDialog(setupFrame, "パスワード（確認）が一致しません。");
				return;
			}
			try {
				AdminAuth.setup(u, p1);
				JOptionPane.showMessageDialog(setupFrame,
						firstTime ? "設定しました。このユーザー名とパスワードでログインしてください。" : "パスワードを変更しました。");
				setupFrame.dispose();
				if (firstTime) {
					openLogin(connection, topFrame);
				}
			} catch (IOException ex) {
				JOptionPane.showMessageDialog(setupFrame, "保存できませんでした：" + ex.getMessage());
			}
		});

		setupFrame.setVisible(true);
	}

	/** ログイン成功後の「管理者画面：データ一覧」を開く。 */
	private static void openAdminFrame(Connection connection) {
		JFrame adminFrame = new JFrame("管理者画面：データ一覧");
		adminFrame.setSize(500, 400);

		JPanel adminListPanel = new JPanel();
		adminListPanel.setLayout(new BoxLayout(adminListPanel, BoxLayout.Y_AXIS));
		loadAdminList(adminListPanel, connection, adminFrame, null);

		JButton trashButton = new JButton("削除済み一覧");
		JButton addButton = new JButton("新規追加");
		JButton exportButton = new JButton("HTML版を書き出す");

		trashButton.addActionListener(tbv -> openTrashWindow(connection));
		addButton.addActionListener(adv -> openAddWindow(connection, adminListPanel, adminFrame));
		exportButton.addActionListener(exv -> exportHtml(connection, adminFrame));

		JTextField adminSearchBox = new JTextField(20);
		JButton adminSearchButton = new JButton("検索");
		JPanel adminSearchRow = new JPanel();
		adminSearchRow.add(adminSearchBox);
		adminSearchRow.add(adminSearchButton);
		adminSearchButton.addActionListener(asv -> loadAdminList(adminListPanel, connection, adminFrame, adminSearchBox.getText()));

		JButton changePasswordButton = new JButton("パスワードを変更");
		changePasswordButton.setFont(new java.awt.Font("Dialog", java.awt.Font.PLAIN, 10));
		changePasswordButton.addActionListener(cpv -> openAccountSetup(connection, null, () -> {
		}));

		JPanel adminMainPanel = new JPanel();
		adminMainPanel.setLayout(new BoxLayout(adminMainPanel, BoxLayout.Y_AXIS));
		adminMainPanel.add(trashButton);
		adminMainPanel.add(addButton);
		adminMainPanel.add(exportButton);
		adminMainPanel.add(changePasswordButton);
		adminMainPanel.add(adminSearchRow);
		adminMainPanel.add(new JScrollPane(adminListPanel));

		adminFrame.add(adminMainPanel);
		adminFrame.setVisible(true);
	}

	/**
	 * データ一覧（works）を、削除済みでないものだけ表示する。
	 * keywordがあれば、作品名または曲名で絞り込む。
	 * 新規追加・編集・削除のあとは、この関数をもう一度呼んで一覧を作り直す（＝再読込）。
	 */
	private static void loadAdminList(JPanel panel, Connection connection, JFrame parentFrame, String keyword) {
		panel.removeAll();
		try {
			String sql = "SELECT * FROM works WHERE deleted = 0";
			if (keyword != null && !keyword.isEmpty()) {
				sql += " AND (作品名 LIKE ? OR id IN (SELECT work_id FROM songData WHERE 曲名 LIKE ?))";
			}
			sql += " ORDER BY 放映開始日";

			PreparedStatement psA = connection.prepareStatement(sql);
			if (keyword != null && !keyword.isEmpty()) {
				psA.setString(1, "%" + keyword + "%");
				psA.setString(2, "%" + keyword + "%");
			}

			ResultSet rsA = psA.executeQuery();
			while (rsA.next()) {
				int workId = rsA.getInt("id");
				String title = rsA.getString("作品名");
				String start = rsA.getString("放映開始日");
				String end = rsA.getString("放映終了日");

				JPanel aRow = new JPanel();
				aRow.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));

				JLabel aLabel = new JLabel(title);
				JButton editButton = new JButton("編集");
				JButton deleteButton = new JButton("削除");

				editButton.addActionListener(edv -> openEditFrame(connection, panel, parentFrame, keyword, workId, title, start, end));

				deleteButton.addActionListener(dv -> {
					int answer = JOptionPane.showConfirmDialog(parentFrame, title + " を削除しますか？", "確認", JOptionPane.YES_NO_OPTION);
					if (answer == JOptionPane.YES_OPTION) {
						try {
							PreparedStatement psD = connection.prepareStatement("UPDATE works SET deleted = 1 WHERE id = ?");
							psD.setInt(1, workId);
							psD.execute();
							loadAdminList(panel, connection, parentFrame, keyword);
						} catch (SQLException ex) {
							JOptionPane.showMessageDialog(parentFrame, "エラー：" + ex.getMessage());
						}
					}
				});

				aRow.add(aLabel);
				aRow.add(editButton);
				aRow.add(deleteButton);
				panel.add(aRow);
			}
		} catch (SQLException ex) {
			panel.add(new JLabel("エラー：" + ex.getMessage()));
		}
		panel.revalidate();
		panel.repaint();
	}

	/**
	 * 1つの作品の「編集」ウィンドウを開く。
	 * ①作品情報（作品名・放映期間）②主題歌（複数・編集・削除・新規追加）
	 * ③CD・音源（複数・編集・削除・新規追加）の3つを、縦にスクロールできる形で並べている。
	 */
	private static void openEditFrame(Connection connection, JPanel listPanel, JFrame parentFrame, String keyword,
			int workId, String title, String start, String end) {

		JFrame editFrame = new JFrame(title + " の編集");
		editFrame.setSize(600, 600);

		JPanel editMainPanel = new JPanel();
		editMainPanel.setLayout(new BoxLayout(editMainPanel, BoxLayout.Y_AXIS));

		// ①作品情報
		JPanel workPanel = new JPanel(new java.awt.GridLayout(4, 2, 5, 5));
		JTextField titleField = new JTextField(title, 20);
		JTextField startField = new JTextField(start, 15);
		JTextField endField = new JTextField(end, 15);
		JButton saveWorkButton = new JButton("作品情報を保存");

		workPanel.add(new JLabel("作品名："));
		workPanel.add(titleField);
		workPanel.add(new JLabel("放映開始日："));
		workPanel.add(startField);
		workPanel.add(new JLabel("放映終了日："));
		workPanel.add(endField);
		workPanel.add(new JLabel(""));
		workPanel.add(saveWorkButton);

		saveWorkButton.addActionListener(sv -> {
			try {
				PreparedStatement psU = connection.prepareStatement("UPDATE works SET 作品名 = ?, 放映開始日 = ?, 放映終了日 = ? WHERE id = ?");
				psU.setString(1, titleField.getText());
				psU.setString(2, startField.getText());
				psU.setString(3, endField.getText());
				psU.setInt(4, workId);
				psU.execute();
				JOptionPane.showMessageDialog(editFrame, "作品情報を保存しました");
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
			}
		});

		editMainPanel.add(workPanel);
		editMainPanel.add(new JLabel("── 主題歌 ──"));

		// ②主題歌の一覧（既存のものを、DBから取り出して1件ずつ表示）
		try {
			PreparedStatement psS = connection.prepareStatement("SELECT rowid, * FROM songData WHERE work_id = ?");
			psS.setInt(1, workId);
			ResultSet rsS = psS.executeQuery();
			while (rsS.next()) {
				editMainPanel.add(buildExistingSongPanel(connection, editFrame, editMainPanel, rsS));
			}
		} catch (SQLException ex) {
			editMainPanel.add(new JLabel("エラー：" + ex.getMessage()));
		}

		// 主題歌の新規追加ボタン
		JButton addSongButton = new JButton("曲を新規追加");
		addSongButton.addActionListener(
				asv -> insertNewSongPanel(connection, editFrame, editMainPanel, addSongButton, workId, titleField, startField, endField));
		editMainPanel.add(addSongButton);

		editMainPanel.add(new JLabel("── CD・音源 ──"));

		// ③CDの一覧（既存のものを、DBから取り出して1件ずつ表示）
		try {
			PreparedStatement psC = connection.prepareStatement("SELECT rowid, * FROM cdData WHERE work_id = ?");
			psC.setInt(1, workId);
			ResultSet rsC = psC.executeQuery();
			while (rsC.next()) {
				editMainPanel.add(buildExistingCdPanel(connection, editFrame, editMainPanel, rsC));
			}
		} catch (SQLException ex) {
			editMainPanel.add(new JLabel("エラー：" + ex.getMessage()));
		}

		// CDの新規追加ボタン
		JButton addCdButton = new JButton("音源を新規追加");
		addCdButton.addActionListener(
				acv -> insertNewCdPanel(connection, editFrame, editMainPanel, addCdButton, workId, titleField, startField, endField));
		editMainPanel.add(addCdButton);

		editFrame.add(new JScrollPane(editMainPanel));
		editFrame.setVisible(true);
	}

	/** 既存の主題歌1件分（区分プルダウン＋各項目＋保存／削除ボタン）の見た目を作る。 */
	private static JPanel buildExistingSongPanel(Connection connection, JFrame editFrame, JPanel editMainPanel, ResultSet rsS)
			throws SQLException {
		long songRowId = rsS.getLong("rowid");

		JPanel songPanel = new JPanel(new java.awt.GridLayout(8, 2, 3, 3));

		JComboBox<String> kubunBox = createKubunBox(rsS.getString("区分_OP1等"));

		JTextField songTitleField = new JTextField(rsS.getString("曲名"), 15);
		JTextField lyricistField = new JTextField(rsS.getString("作詞者"), 15);
		JTextField composerField = new JTextField(rsS.getString("作曲者"), 15);
		JTextField arrangerField = new JTextField(rsS.getString("編曲者"), 15);
		JTextField singerField = new JTextField(rsS.getString("歌唱者"), 15);
		JTextField songNoteField = new JTextField(rsS.getString("備考"), 15);
		JButton saveSongButton = new JButton("この曲を保存");
		JButton deleteSongButton = new JButton("この曲を削除");

		songPanel.add(new JLabel("区分："));
		songPanel.add(kubunBox);
		songPanel.add(new JLabel("曲名："));
		songPanel.add(songTitleField);
		songPanel.add(new JLabel("作詞者："));
		songPanel.add(lyricistField);
		songPanel.add(new JLabel("作曲者："));
		songPanel.add(composerField);
		songPanel.add(new JLabel("編曲者："));
		songPanel.add(arrangerField);
		songPanel.add(new JLabel("歌唱者："));
		songPanel.add(singerField);
		songPanel.add(new JLabel("備考："));
		songPanel.add(songNoteField);

		JPanel songButtonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		songButtonPanel.add(saveSongButton);
		songButtonPanel.add(deleteSongButton);
		songPanel.add(new JLabel(""));
		songPanel.add(songButtonPanel);

		// 区分の種類（OP／ED等）に応じて、行の背景色を変える（起動時と、入力のたびに更新）
		songPanel.setBackground(kubunColor(kubunText(kubunBox)));
		watchKubunColor(kubunBox, songPanel);

		saveSongButton.addActionListener(ssv -> {
			try {
				PreparedStatement psSU = connection.prepareStatement(
						"UPDATE songData SET 区分_OP1等 = ?, 曲名 = ?, 作詞者 = ?, 作曲者 = ?, 編曲者 = ?, 歌唱者 = ?, 備考 = ? WHERE rowid = ?");
				psSU.setString(1, kubunText(kubunBox));
				psSU.setString(2, songTitleField.getText());
				psSU.setString(3, lyricistField.getText());
				psSU.setString(4, composerField.getText());
				psSU.setString(5, arrangerField.getText());
				psSU.setString(6, singerField.getText());
				psSU.setString(7, songNoteField.getText());
				psSU.setLong(8, songRowId);
				psSU.execute();
				JOptionPane.showMessageDialog(editFrame, "曲情報を保存しました");
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
			}
		});

		deleteSongButton.addActionListener(dsv -> {
			int answer = JOptionPane.showConfirmDialog(editFrame, "この曲を削除しますか？", "確認", JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION) {
				try {
					PreparedStatement psSD = connection.prepareStatement("DELETE FROM songData WHERE rowid = ?");
					psSD.setLong(1, songRowId);
					psSD.execute();
					editMainPanel.remove(songPanel);
					editMainPanel.revalidate();
					editMainPanel.repaint();
				} catch (SQLException ex) {
					JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
				}
			}
		});

		return songPanel;
	}

	/** 「曲を新規追加」ボタンが押されたとき、空の入力欄をその場（追加ボタンの直前）に差し込む。 */
	private static void insertNewSongPanel(Connection connection, JFrame editFrame, JPanel editMainPanel, JButton addSongButton,
			int workId, JTextField titleField, JTextField startField, JTextField endField) {

		JPanel newSongPanel = new JPanel(new java.awt.GridLayout(8, 2, 3, 3));

		JComboBox<String> newKubunBox = createKubunBox("");

		JTextField newSongTitleField = new JTextField("", 15);
		JTextField newLyricistField = new JTextField("", 15);
		JTextField newComposerField = new JTextField("", 15);
		JTextField newArrangerField = new JTextField("", 15);
		JTextField newSingerField = new JTextField("", 15);
		JTextField newNoteField = new JTextField("", 15);
		JButton newSaveButton = new JButton("この曲を保存");
		JButton newCancelButton = new JButton("取消");

		newSongPanel.add(new JLabel("区分："));
		newSongPanel.add(newKubunBox);
		newSongPanel.add(new JLabel("曲名："));
		newSongPanel.add(newSongTitleField);
		newSongPanel.add(new JLabel("作詞者："));
		newSongPanel.add(newLyricistField);
		newSongPanel.add(new JLabel("作曲者："));
		newSongPanel.add(newComposerField);
		newSongPanel.add(new JLabel("編曲者："));
		newSongPanel.add(newArrangerField);
		newSongPanel.add(new JLabel("歌唱者："));
		newSongPanel.add(newSingerField);
		newSongPanel.add(new JLabel("備考："));
		newSongPanel.add(newNoteField);

		JPanel newSongButtonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		newSongButtonPanel.add(newSaveButton);
		newSongButtonPanel.add(newCancelButton);
		newSongPanel.add(new JLabel(""));
		newSongPanel.add(newSongButtonPanel);

		newSongPanel.setBackground(kubunColor(kubunText(newKubunBox)));
		watchKubunColor(newKubunBox, newSongPanel);

		newSaveButton.addActionListener(nsv -> {
			try {
				PreparedStatement psNS = connection.prepareStatement(
						"INSERT INTO songData (作品名, 放映開始日, 放映終了日, work_id, 区分_OP1等, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
				psNS.setString(1, titleField.getText());
				psNS.setString(2, startField.getText());
				psNS.setString(3, endField.getText());
				psNS.setInt(4, workId);
				psNS.setString(5, kubunText(newKubunBox));
				psNS.setString(6, newSongTitleField.getText());
				psNS.setString(7, newLyricistField.getText());
				psNS.setString(8, newComposerField.getText());
				psNS.setString(9, newArrangerField.getText());
				psNS.setString(10, newSingerField.getText());
				psNS.setString(11, newNoteField.getText());
				psNS.execute();
				newSaveButton.setText("保存済み");
				newSaveButton.setEnabled(false);
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
			}
		});

		newCancelButton.addActionListener(ncv -> {
			editMainPanel.remove(newSongPanel);
			editMainPanel.revalidate();
			editMainPanel.repaint();
		});

		int index = editMainPanel.getComponentZOrder(addSongButton);
		editMainPanel.add(newSongPanel, index);
		editMainPanel.revalidate();
		editMainPanel.repaint();
	}

	/** 既存のCD・音源1件分（各項目＋保存／削除ボタン）の見た目を作る。 */
	private static JPanel buildExistingCdPanel(Connection connection, JFrame editFrame, JPanel editMainPanel, ResultSet rsC)
			throws SQLException {
		long cdRowId = rsC.getLong("rowid");

		JPanel cdEditPanel = new JPanel(new java.awt.GridLayout(7, 2, 3, 3));

		JTextField cdTitleField = new JTextField(rsC.getString("CDタイトル"), 15);
		JTextField tracksField = new JTextField(rsC.getString("収録曲"), 15);
		JTextField urlField = new JTextField(rsC.getString("購入URL"), 15);
		JTextField companyField = new JTextField(rsC.getString("発売会社"), 15);
		JTextField numberField = new JTextField(rsC.getString("品番"), 15);
		JTextField cdNoteField = new JTextField(rsC.getString("備考"), 15);
		JButton saveCdButton = new JButton("この音源を保存");
		JButton deleteCdButton = new JButton("この音源を削除");

		JPanel cdButtonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		cdButtonPanel.add(saveCdButton);
		cdButtonPanel.add(deleteCdButton);

		cdEditPanel.add(new JLabel("CDタイトル："));
		cdEditPanel.add(cdTitleField);
		cdEditPanel.add(new JLabel("収録曲："));
		cdEditPanel.add(tracksField);
		cdEditPanel.add(new JLabel("購入URL："));
		cdEditPanel.add(urlField);
		cdEditPanel.add(new JLabel("発売会社："));
		cdEditPanel.add(companyField);
		cdEditPanel.add(new JLabel("品番："));
		cdEditPanel.add(numberField);
		cdEditPanel.add(new JLabel("備考："));
		cdEditPanel.add(cdNoteField);
		cdEditPanel.add(new JLabel(""));
		cdEditPanel.add(cdButtonPanel);

		saveCdButton.addActionListener(scv -> {
			try {
				PreparedStatement psCU = connection.prepareStatement(
						"UPDATE cdData SET CDタイトル = ?, 収録曲 = ?, 購入URL = ?, 発売会社 = ?, 品番 = ?, 備考 = ? WHERE rowid = ?");
				psCU.setString(1, cdTitleField.getText());
				psCU.setString(2, tracksField.getText());
				psCU.setString(3, urlField.getText());
				psCU.setString(4, companyField.getText());
				psCU.setString(5, numberField.getText());
				psCU.setString(6, cdNoteField.getText());
				psCU.setLong(7, cdRowId);
				psCU.execute();
				JOptionPane.showMessageDialog(editFrame, "CD情報を保存しました");
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
			}
		});

		deleteCdButton.addActionListener(dcv -> {
			int answer = JOptionPane.showConfirmDialog(editFrame, "このCD情報を削除しますか？", "確認", JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION) {
				try {
					PreparedStatement psCD = connection.prepareStatement("DELETE FROM cdData WHERE rowid = ?");
					psCD.setLong(1, cdRowId);
					psCD.execute();
					editMainPanel.remove(cdEditPanel);
					editMainPanel.remove(cdButtonPanel);
					editMainPanel.revalidate();
					editMainPanel.repaint();
				} catch (SQLException ex) {
					JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
				}
			}
		});

		return cdEditPanel;
	}

	/** 「音源を新規追加」ボタンが押されたとき、空の入力欄をその場（追加ボタンの直前）に差し込む。 */
	private static void insertNewCdPanel(Connection connection, JFrame editFrame, JPanel editMainPanel, JButton addCdButton,
			int workId, JTextField titleField, JTextField startField, JTextField endField) {

		JPanel newCdPanel = new JPanel(new java.awt.GridLayout(7, 2, 3, 3));

		JTextField newCdTitleField = new JTextField("", 15);
		JTextField newTracksField = new JTextField("", 15);
		JTextField newUrlField = new JTextField("", 15);
		JTextField newCompanyField = new JTextField("", 15);
		JTextField newNumberField = new JTextField("", 15);
		JTextField newCdNoteField = new JTextField("", 15);
		JButton newSaveCdButton = new JButton("この音源を保存");
		JButton newCancelCdButton = new JButton("取消");

		JPanel newCdButtonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		newCdButtonPanel.add(newSaveCdButton);
		newCdButtonPanel.add(newCancelCdButton);

		newCdPanel.add(new JLabel("CDタイトル："));
		newCdPanel.add(newCdTitleField);
		newCdPanel.add(new JLabel("収録曲："));
		newCdPanel.add(newTracksField);
		newCdPanel.add(new JLabel("購入URL："));
		newCdPanel.add(newUrlField);
		newCdPanel.add(new JLabel("発売会社："));
		newCdPanel.add(newCompanyField);
		newCdPanel.add(new JLabel("品番："));
		newCdPanel.add(newNumberField);
		newCdPanel.add(new JLabel("備考："));
		newCdPanel.add(newCdNoteField);
		newCdPanel.add(new JLabel(""));
		newCdPanel.add(newCdButtonPanel);

		newSaveCdButton.addActionListener(nscv -> {
			try {
				PreparedStatement psNC = connection.prepareStatement(
						"INSERT INTO cdData (作品名, 放映開始日, 放映終了日, work_id, CDタイトル, 収録曲, 購入URL, 発売会社, 品番, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
				psNC.setString(1, titleField.getText());
				psNC.setString(2, startField.getText());
				psNC.setString(3, endField.getText());
				psNC.setInt(4, workId);
				psNC.setString(5, newCdTitleField.getText());
				psNC.setString(6, newTracksField.getText());
				psNC.setString(7, newUrlField.getText());
				psNC.setString(8, newCompanyField.getText());
				psNC.setString(9, newNumberField.getText());
				psNC.setString(10, newCdNoteField.getText());
				psNC.execute();
				newSaveCdButton.setText("保存済み");
				newSaveCdButton.setEnabled(false);
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(editFrame, "エラー：" + ex.getMessage());
			}
		});

		newCancelCdButton.addActionListener(nccv -> {
			editMainPanel.remove(newCdPanel);
			editMainPanel.revalidate();
			editMainPanel.repaint();
		});

		int index = editMainPanel.getComponentZOrder(addCdButton);
		editMainPanel.add(newCdPanel, index);
		editMainPanel.revalidate();
		editMainPanel.repaint();
	}

	/** 「削除済み一覧」ウィンドウを開く。復活／完全削除ができる。 */
	private static void openTrashWindow(Connection connection) {
		JFrame trashFrame = new JFrame("削除済み一覧");
		trashFrame.setSize(500, 400);

		JPanel trashListPanel = new JPanel();
		trashListPanel.setLayout(new BoxLayout(trashListPanel, BoxLayout.Y_AXIS));

		try {
			PreparedStatement psT = connection.prepareStatement("SELECT * FROM works WHERE deleted = 1 ORDER BY 放映開始日");
			ResultSet rsT = psT.executeQuery();
			while (rsT.next()) {
				int tWorkId = rsT.getInt("id");
				String tTitle = rsT.getString("作品名");

				JPanel tRow = new JPanel();
				tRow.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));

				JLabel tLabel = new JLabel(tTitle);
				JButton restoreButton = new JButton("復活");
				JButton hardDeleteButton = new JButton("完全削除");

				restoreButton.addActionListener(rv -> {
					try {
						PreparedStatement psR = connection.prepareStatement("UPDATE works SET deleted = 0 WHERE id = ?");
						psR.setInt(1, tWorkId);
						psR.execute();
						trashListPanel.remove(tRow);
						trashListPanel.revalidate();
						trashListPanel.repaint();
					} catch (SQLException ex) {
						JOptionPane.showMessageDialog(trashFrame, "エラー：" + ex.getMessage());
					}
				});

				hardDeleteButton.addActionListener(hv -> {
					int answer = JOptionPane.showConfirmDialog(trashFrame, tTitle + " を完全に削除します。元に戻せません。よろしいですか？", "確認",
							JOptionPane.YES_NO_OPTION);
					if (answer == JOptionPane.YES_OPTION) {
						try {
							PreparedStatement psH = connection.prepareStatement("DELETE FROM works WHERE id = ?");
							psH.setInt(1, tWorkId);
							psH.execute();
							trashListPanel.remove(tRow);
							trashListPanel.revalidate();
							trashListPanel.repaint();
						} catch (SQLException ex) {
							JOptionPane.showMessageDialog(trashFrame, "エラー：" + ex.getMessage());
						}
					}
				});

				tRow.add(tLabel);
				tRow.add(restoreButton);
				tRow.add(hardDeleteButton);
				trashListPanel.add(tRow);
			}
		} catch (SQLException ex) {
			trashListPanel.add(new JLabel("エラー：" + ex.getMessage()));
		}

		trashFrame.add(new JScrollPane(trashListPanel));
		trashFrame.setVisible(true);
	}

	/** 「新規追加」ウィンドウ（作品そのものを1件追加する）を開く。 */
	private static void openAddWindow(Connection connection, JPanel adminListPanel, JFrame adminFrame) {
		JFrame addFrame = new JFrame("新規作品の追加");
		addFrame.setSize(350, 200);
		addFrame.setLayout(new java.awt.GridLayout(4, 2, 5, 5));

		JTextField newTitleField = new JTextField();
		JTextField newStartField = new JTextField();
		JTextField newEndField = new JTextField();
		JButton newSaveButton = new JButton("保存");

		addFrame.add(new JLabel("作品名："));
		addFrame.add(newTitleField);
		addFrame.add(new JLabel("放映開始日："));
		addFrame.add(newStartField);
		addFrame.add(new JLabel("放映終了日："));
		addFrame.add(newEndField);
		addFrame.add(new JLabel(""));
		addFrame.add(newSaveButton);

		newSaveButton.addActionListener(nsv -> {
			try {
				PreparedStatement psN = connection.prepareStatement("INSERT INTO works (作品名, 放映開始日, 放映終了日) VALUES (?, ?, ?)");
				psN.setString(1, newTitleField.getText());
				psN.setString(2, newStartField.getText());
				psN.setString(3, newEndField.getText());
				psN.execute();

				JOptionPane.showMessageDialog(addFrame, "登録しました");
				loadAdminList(adminListPanel, connection, adminFrame, null);
				addFrame.dispose();
			} catch (SQLException ex) {
				JOptionPane.showMessageDialog(addFrame, "エラー：" + ex.getMessage());
			}
		});

		addFrame.setVisible(true);
	}

	/**
	 * 「HTML版を書き出す」ボタンの処理。
	 * DBの内容をJSON（JavaScriptが読める形のデータ）に変換し、検索・年代ボタン・
	 * 主題歌の色分け・Amazonリンクなどをすべて含んだ1枚のHTML（top.html）として保存する。
	 */
	private static void exportHtml(Connection connection, JFrame adminFrame) {
		StringBuilder json = new StringBuilder("[");

		try {
			PreparedStatement psE = connection.prepareStatement("SELECT * FROM works WHERE deleted = 0 ORDER BY 放映開始日");
			ResultSet rsE = psE.executeQuery();
			boolean first = true;

			while (rsE.next()) {
				if (!first)
					json.append(",");
				first = false;

				json.append("{");
				json.append("\"title\":\"").append(jsonEscape(rsE.getString("作品名"))).append("\",");
				json.append("\"start\":\"").append(jsonEscape(rsE.getString("放映開始日"))).append("\",");
				json.append("\"end\":\"").append(jsonEscape(rsE.getString("放映終了日"))).append("\",");

				int workId = rsE.getInt("id");

				json.append("\"songs\":[");
				PreparedStatement psES = connection.prepareStatement("SELECT * FROM songData WHERE work_id = ?");
				psES.setInt(1, workId);
				ResultSet rsES = psES.executeQuery();
				boolean firstSong = true;
				while (rsES.next()) {
					if (!firstSong)
						json.append(",");
					firstSong = false;
					json.append("{\"kubun\":\"").append(jsonEscape(rsES.getString("区分_OP1等")))
							.append("\",\"songTitle\":\"").append(jsonEscape(rsES.getString("曲名")))
							.append("\",\"lyricist\":\"").append(jsonEscape(rsES.getString("作詞者")))
							.append("\",\"composer\":\"").append(jsonEscape(rsES.getString("作曲者")))
							.append("\",\"arranger\":\"").append(jsonEscape(rsES.getString("編曲者")))
							.append("\",\"singer\":\"").append(jsonEscape(rsES.getString("歌唱者")))
							.append("\",\"note\":\"").append(jsonEscape(rsES.getString("備考"))).append("\"}");
				}
				json.append("],");

				json.append("\"cds\":[");
				PreparedStatement psEC = connection.prepareStatement("SELECT * FROM cdData WHERE work_id = ?");
				psEC.setInt(1, workId);
				ResultSet rsEC = psEC.executeQuery();
				boolean firstCd = true;
				while (rsEC.next()) {
					if (!firstCd)
						json.append(",");
					firstCd = false;
					json.append("{\"cdTitle\":\"").append(jsonEscape(rsEC.getString("CDタイトル")))
							.append("\",\"hinban\":\"").append(jsonEscape(rsEC.getString("品番")))
							.append("\",\"company\":\"").append(jsonEscape(rsEC.getString("発売会社")))
							.append("\",\"tracks\":\"").append(jsonEscape(rsEC.getString("収録曲")))
							.append("\",\"note\":\"").append(jsonEscape(rsEC.getString("備考"))).append("\"}");
				}
				json.append("]");
				json.append("}");
			}
		} catch (SQLException ex) {
			JOptionPane.showMessageDialog(adminFrame, "エラー：" + ex.getMessage());
			return;
		}

		json.append("]");

		// ここから下は、ブラウザで表示するための1枚のHTML（見た目のCSS＋動きのJavaScript入り）。
		// 「const works = ...」の部分に、上で作ったJSONデータをそのまま埋め込んでいる。
		String html = """
				<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>
				<title>アニ伝アーカイブ</title>
				<style>
				body{margin:0;font-family:'Segoe UI',sans-serif;background:#f2f2f2;}
				header{
				  background: repeating-linear-gradient(0deg,#cfcfae,#cfcfae 20px,#bdbd9a 20px,#bdbd9a 22px),
				              repeating-linear-gradient(90deg,transparent,transparent 78px,#a9a985 78px,#a9a985 80px);
				  padding:30px 20px;text-align:center;border-bottom:4px solid #6b5b95;
				}
				header h1{font-size:2.4em;margin:0;color:#5b3fa0;text-shadow:2px 2px 0 #d4af37,4px 4px 6px rgba(0,0,0,0.3);letter-spacing:4px;}
				header p{color:#444;margin-top:8px;font-size:0.9em;}
				header p.origin{font-size:0.75em;color:#777;margin-top:2px;font-style:italic;}
				main{max-width:900px;margin:0 auto;padding:20px;}
				#searchArea{text-align:center;margin-bottom:20px;}
				input#searchBox{padding:8px;width:250px;font-size:14px;}
				#eraButtons button,#yearButtons button{margin:4px;padding:8px 14px;border:none;border-radius:6px;background:#6b5b95;color:white;cursor:pointer;}
				#eraButtons button:hover,#yearButtons button:hover{background:#5b3fa0;}
				button{cursor:pointer;}
				.card{background:white;border-radius:8px;padding:14px;margin:12px 0;box-shadow:0 2px 5px rgba(0,0,0,0.15);}
				.card h3{margin:0 0 4px 0;color:#333;}
				.period{font-size:0.85em;color:#888;margin-bottom:8px;}
				.song{font-size:0.9em;color:#555;margin-left:8px;}
				.mediaBtn{margin-top:8px;background:#d4af37;border:none;padding:6px 12px;border-radius:5px;}
				.cdlist{display:none;margin-top:8px;padding-left:10px;border-left:3px solid #d4af37;}
				.cdlist.show{display:block;}
				.cdrow{margin:4px 0;}
				.about-link{text-align:center;margin:6px 0 0;}
				.about-link a{color:#5b3fa0;font-size:0.85em;text-decoration:none;cursor:pointer;}
				.about-link a:hover{text-decoration:underline;}
				.about-box{display:none;max-width:860px;margin:14px auto;padding:18px 22px;background:#fffdf7;border:1px solid #e0d7bd;border-radius:8px;line-height:1.8;font-size:0.9em;color:#444;}
				.about-box.show{display:block;}
				.about-box h2{color:#5b3fa0;font-size:1.05em;border-bottom:2px solid #d4af37;padding-bottom:4px;margin:22px 0 10px;}
				.about-box h2:first-child{margin-top:0;}
				.about-box p{margin:0 0 10px;}
				.about-box ol{margin:0;padding-left:1.3em;}
				.about-box li{margin:0 0 10px;}
				</style></head><body>
				<header>
				  <h1>アニ伝アーカイブ</h1>
				  <p>昔懐かしいアニメ主題歌・音源情報のデータベース</p>
				  <p class='origin'>旧Webサイト「アニメ主題歌伝道室」を現代版として復活させました。</p>
				  <p class='about-link'><a onclick='toggleAbout()'>■ このサイトについて（作成意図・TVオリジナルの定義・データについて・注意事項）</a></p>
				</header>
				<div id='aboutBox' class='about-box'>
				"""
				+ AboutContent.BODY_HTML
				+ """
				<p style='text-align:center;margin-top:18px;'><button onclick='toggleAbout()' style='background:#5b3fa0;color:white;border:none;padding:8px 20px;border-radius:5px;cursor:pointer;'>閉じる</button></p>
				</div>
				<main>
				<div id='oldMessage' style='max-width:600px;margin:0 auto 20px;text-align:center;color:#5b3fa0;line-height:1.8;font-size:0.95em;'>
						「鉄腕アトム」以来約1000曲以上のアニメ主題歌が作られていると言われている<br>
						あなたはそのうちいったい、何曲を聴いたことがあるだろうか<br>
						埋もれて陽の目を見ないでいるアニメ音楽の中から、自分の好みに合う曲を探してみるのもよいではなかろうか<br>
						次のような言葉がある<br>
						音楽にジャンルやレベルの違いなど存在しない<br>
						胸に染みるか、染みないか<br>
						歌に力があるか、無いか<br>
						大切なのはそれだけである
				</div>
				  <div id='searchArea'>
				    <input id='searchBox' placeholder='作品の場合、数字は全角入力を推奨'>
				    <button onclick='doSearch()'>検索</button>
				    <button onclick='resetToTop()'>TOPに戻る</button>
				  </div>
				  <div id='eraButtons' style='text-align:center;'></div>
				  <div id='yearButtons' style='text-align:center;display:none;margin-top:10px;'></div>
				  <div id='results'></div>
				</main>
				<script>
				const works = """
				+ json.toString()
				+ """
				;

				function decadeOf(startStr){
				  const y = parseInt(startStr.substring(0,4));
				  return Math.floor(y/10)*10;
				}

				const decades = [...new Set(works.map(w => decadeOf(w.start)))].sort((a,b)=>a-b);

				function buildEraButtons(){
				  const wrap = document.getElementById('eraButtons');
				  wrap.innerHTML = '';
				  decades.forEach(d => {
				    const btn = document.createElement('button');
				    btn.textContent = d + '年代';
				    btn.onclick = () => showYears(d);
				    wrap.appendChild(btn);
				  });
				}

				function showYears(decade){
				  document.getElementById('oldMessage').style.display = 'none';
				  const wrap = document.getElementById('yearButtons');
				  wrap.innerHTML = '';
				  wrap.style.display = 'block';
				  for (let y = decade; y < decade + 10; y++){
				    const btn = document.createElement('button');
				    btn.textContent = y + '年';
				    btn.onclick = () => {
				      const filtered = works.filter(w => w.start.startsWith(String(y)));
				      if (filtered.length === 0){
				        document.getElementById('results').innerHTML = '<p>作成中</p>';
				      } else {
				        render(filtered);
				      }
				    };
				    wrap.appendChild(btn);
				  }
				}

				function toHalfWidthDigits(s){
				  return s.replace(/[０-９]/g, d => String.fromCharCode(d.charCodeAt(0) - 0xFEE0));
				}
				function toFullWidthDigits(s){
				  return s.replace(/[0-9]/g, d => String.fromCharCode(d.charCodeAt(0) + 0xFEE0));
				}

				function kubunColor(kubun){
				  const k = kubun || '';
				  const hasOp = k.includes('OP');
				  const hasEd = k.includes('ED');
				  if (hasOp) return '#ffb3ba'; // OP（混合も含む）：赤系パステル
				  if (hasEd) return '#aec6ff'; // ED単独：青系パステル
				  return '#ffffff'; // 挿入歌・イメージソングなどは白
				}

				function cleanForAmazon(title){
				  return title.replace(/（[^）]*）/g,'').replace(/\\([^)]*\\)/g,'').replace(/～/g,' ').replace(/・/g,' ').replace(/･/g,' ').trim();
				}
				function buildAmazonUrl(hinban, cdTitle){
				  const q = cleanForAmazon(cdTitle);
				  return 'https://www.amazon.co.jp/s?k=' + encodeURIComponent(q.trim());
				}

				function cdDisplayTitle(cdTitle){
				  if (/^初盤(\\(.*\\))?$/.test((cdTitle||'').trim())) {
				    return cdTitle + '（作品と同名タイトル）';
				  }
				  return cdTitle;
				}
				function matchesKeyword(w, kw){
				  if (!kw) return true;
				  if (w.title.includes(kw)) return true;
				  for (const s of w.songs){
				    if ([s.kubun,s.songTitle,s.lyricist,s.composer,s.arranger,s.singer,s.note].some(v => v && v.includes(kw))) return true;
				  }
				  for (const c of w.cds){
				    if ([c.cdTitle,c.hinban,c.company,c.tracks,c.note].some(v => v && v.includes(kw))) return true;
				  }
				  return false;
				}

				function render(list){
				  const area = document.getElementById('results');
				  area.innerHTML = '';
				  list.forEach((w, idx) => {
				    const div = document.createElement('div');
				    div.className = 'card';
				    const songsHtml = w.songs.map(s => `<div class='song' style='background:${kubunColor(s.kubun)};padding:6px;border-radius:5px;margin-bottom:4px;'>${s.kubun} ${s.songTitle}（${s.singer}）<br>作詞：${s.lyricist} ／ 作曲：${s.composer} ／ 編曲：${s.arranger}<br>備考：${s.note}</div>`).join('');
				    const cdId = 'cd_' + idx;
				    const cdHtml = w.cds.map(c => `<div class='cdrow' style='background:#fff3cd;padding:8px;border-radius:5px;margin-bottom:6px;border-left:4px solid #d4af37;'>${cdDisplayTitle(c.cdTitle)} <a href='${buildAmazonUrl(c.hinban, c.cdTitle)}' target='_blank'><button type='button'>Amazon</button></a><br>品番：${c.hinban} ／ 発売会社：${c.company}<br>収録曲：${(c.tracks||'').replace(/\\n/g,'、')}<br>備考：${c.note}</div>`).join('') || '（音源情報なし）';
				    div.innerHTML = `<h3>${w.title}</h3><div class='period'>${w.start}～${w.end}</div>${songsHtml}<button class='mediaBtn' onclick="document.getElementById('${cdId}').classList.toggle('show')">音源詳細</button><div id='${cdId}' class='cdlist'>${cdHtml}</div>`;
				    area.appendChild(div);
				  });
				}

				function toggleAbout(){
				  document.getElementById('aboutBox').classList.toggle('show');
				}

				function resetToTop(){
				  document.getElementById('oldMessage').style.display = 'block';
				  document.getElementById('searchBox').value = '';
				  document.getElementById('yearButtons').style.display = 'none';
				  render(works);
				}

				function doSearch(){
				  document.getElementById('oldMessage').style.display = 'none';
				  const kwRaw = document.getElementById('searchBox').value;
				  const kwHalf = toHalfWidthDigits(kwRaw);
				  const kwFull = toFullWidthDigits(kwRaw);
				  document.getElementById('yearButtons').style.display = 'none';
				  render(works.filter(w => matchesKeyword(w, kwHalf) || matchesKeyword(w, kwFull)));
				}

				document.getElementById('searchBox').addEventListener('keydown', function(e){
				  if (e.key === 'Enter') doSearch();
				});

				buildEraButtons();
				render(works);
				</script>
				</body></html>
				""";

		// test.dbと同じフォルダに書き出す（どこから起動しても、同じ場所になる）
		try {
			File out = new File(Database.dataFolder(), "top.html");
			Files.writeString(out.toPath(), html);
			JOptionPane.showMessageDialog(adminFrame, "top.html を書き出しました。\n場所：" + out.getAbsolutePath());
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(adminFrame, "エラー：" + ex.getMessage());
		}
	}

	// ==================== 区分（OP／ED等）を扱う小さな部品たち ====================

	/** 区分の種類（プルダウンの選択肢の文字）から、行の背景色を決める。 */
	private static java.awt.Color kubunColor(String kubun) {
		if (kubun == null)
			return java.awt.Color.WHITE;
		if (kubun.contains("OP"))
			return new java.awt.Color(255, 179, 186); // OP（混合も含む）：赤系パステル
		if (kubun.contains("ED"))
			return new java.awt.Color(174, 198, 255); // ED単独：青系パステル
		return java.awt.Color.WHITE;
	}

	/**
	 * 区分（OP／ED等）の入力欄を作る。よく使う形を候補として選べるが、
	 * 候補にない形（数字を足す、（）で囲むなど）は、そのまま文字を直接入力・編集できる。
	 * 保存するときは、この欄に書かれている文字を、そのままDBに入れる。
	 */
	private static JComboBox<String> createKubunBox(String initialValue) {
		JComboBox<String> box = new JComboBox<>(new String[] { "OP", "ED", "OP・ED", "挿入歌", "イメージソング", "(OP)", "(ED)", "" });
		box.setEditable(true);
		box.setSelectedItem(initialValue == null ? "" : initialValue);
		return box;
	}

	/** 区分の入力欄に、今まさに書かれている文字を取り出す（候補を選んだだけでも、直接打ち込んだ途中でも拾える）。 */
	private static String kubunText(JComboBox<String> box) {
		Object item = box.getEditor().getItem();
		return item == null ? "" : item.toString();
	}

	/** 区分の入力欄の文字が変わるたびに（候補を選んだときも、1文字打つたびも）、行の背景色を更新する。 */
	private static void watchKubunColor(JComboBox<String> box, JPanel panelToColor) {
		box.addActionListener(e -> panelToColor.setBackground(kubunColor(kubunText(box))));
		javax.swing.text.JTextComponent editor = (javax.swing.text.JTextComponent) box.getEditor().getEditorComponent();
		editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			private void update() {
				panelToColor.setBackground(kubunColor(kubunText(box)));
			}

			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				update();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				update();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				update();
			}
		});
	}

	/** JSONの中に文字をそのまま入れても壊れないように、特別な文字を安全な形に変換する。 */
	private static String jsonEscape(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}
}
