package jp.example;

import java.awt.Desktop;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * 一般ユーザー向けのTOPページ。
 * ・検索（作品名／曲名／CD情報など、すべての項目が対象）
 * ・年代ボタン → 年ボタン → その年の作品一覧
 * ・作品一覧の各行から「楽曲詳細」→「音源詳細」の順に、別ウィンドウが開いていく
 * ・右下の「管理者ログイン」ボタンから、AdminWindow（管理者画面）へ進む
 */
public class TopWindow {

	// ---- 画面全体の色（HTML版と同じ配色にそろえている） ----
	static final Color PURPLE = new Color(0x5b, 0x3f, 0xa0);
	static final Color PURPLE_DARK = new Color(0x45, 0x2f, 0x7a);
	static final Color GOLD = new Color(0xc9, 0x9a, 0x2e);
	static final Color CREAM = new Color(0xf7, 0xf3, 0xe8);
	static final Color CARD_BORDER = new Color(0xe0, 0xdc, 0xd0);
	static final Color INK = new Color(0x33, 0x33, 0x33);

	/** TOPページのウィンドウ（JFrame）を組み立てて返す。表示（setVisible）はまだしない。 */
	public static JFrame build(Connection connection) {

		JFrame frame = new JFrame("アニ伝アーカイブ");
		frame.setSize(700, 560);
		frame.setMinimumSize(new Dimension(560, 420));
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setLayout(new BorderLayout());

		// ==================== 上部：タイトル・検索・年代ボタン ====================
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(CREAM);
		header.setBorder(new EmptyBorder(0, 0, 10, 0));

		// ---- タイトル帯 ----
		JPanel titleBar = new JPanel(new BorderLayout());
		titleBar.setBackground(PURPLE);
		titleBar.setBorder(new EmptyBorder(14, 20, 14, 20));
		JLabel titleLabel = new JLabel("アニ伝アーカイブ");
		titleLabel.setFont(new Font("Meiryo", Font.BOLD, 22));
		titleLabel.setForeground(Color.WHITE);
		JLabel subtitleLabel = new JLabel("昔懐かしいアニメ主題歌・音源情報のデータベース");
		subtitleLabel.setFont(new Font("Meiryo", Font.PLAIN, 11));
		subtitleLabel.setForeground(new Color(0xe4, 0xd9, 0xf7));
		JPanel titleTextBox = new JPanel();
		titleTextBox.setOpaque(false);
		titleTextBox.setLayout(new BoxLayout(titleTextBox, BoxLayout.Y_AXIS));
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleTextBox.add(titleLabel);
		titleTextBox.add(subtitleLabel);
		titleBar.add(titleTextBox, BorderLayout.WEST);
		header.add(titleBar);

		// ---- 検索欄 ----
		JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
		searchRow.setBackground(CREAM);
		JTextField searchBox = new JTextField(22);
		searchBox.setFont(new Font("Meiryo", Font.PLAIN, 13));
		JButton searchButton = styledButton("検索", PURPLE);
		searchRow.add(searchBox);
		searchRow.add(searchButton);
		header.add(searchRow);

		JLabel searchNoteLabel = new JLabel("※作品の場合、数字は全角入力を推奨。", javax.swing.SwingConstants.CENTER);
		searchNoteLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
		searchNoteLabel.setForeground(Color.GRAY);
		searchNoteLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		header.add(searchNoteLabel);

		// ---- 年代ボタン ----
		JPanel eraRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
		eraRow.setBackground(CREAM);
		JButton era1960Button = styledButton("1960年代", GOLD);
		JButton era1970Button = styledButton("1970年代", GOLD);
		eraRow.add(era1960Button);
		eraRow.add(era1970Button);
		header.add(eraRow);

		frame.add(header, BorderLayout.NORTH);

		// ==================== 中央：検索結果（スクロール付き） ====================
		JPanel resultPanel = new JPanel();
		resultPanel.setLayout(new BoxLayout(resultPanel, BoxLayout.Y_AXIS));
		resultPanel.setBackground(Color.WHITE);
		resultPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JScrollPane scrollPane = new JScrollPane(resultPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		frame.add(scrollPane, BorderLayout.CENTER);

		// ==================== 下部：このサイトについて／管理者ログイン ====================
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBackground(CREAM);
		footer.setBorder(new EmptyBorder(6, 12, 6, 12));

		JButton aboutButton = smallLinkButton("このサイトについて");
		aboutButton.addActionListener(av -> openAboutWindow());
		footer.add(aboutButton, BorderLayout.WEST);

		JButton adminButton = smallLinkButton("管理者ログイン");
		adminButton.addActionListener(av -> AdminWindow.openLogin(connection, frame));
		footer.add(adminButton, BorderLayout.EAST);

		frame.add(footer, BorderLayout.SOUTH);

		// 1962〜1969年（このデータでは1960・1961年の作品が存在しないため1962年から）
		era1960Button.addActionListener(ev -> openEraWindow(connection, frame, 1962, 1969, "1960年代"));
		// 1970〜1979年
		era1970Button.addActionListener(ev -> openEraWindow(connection, frame, 1970, 1979, "1970年代"));
		// 1980年代（予定）

		// ---- 検索ボタンの動き ----
		searchButton.addActionListener(e -> runSearch(connection, frame, searchBox, resultPanel));
		// 検索欄でEnterキーを押しても、検索ボタンと同じ動きにする
		searchBox.addActionListener(e -> searchButton.doClick());

		return frame;
	}

	/** 色付きの、少し現代風なボタンを作る（検索・年代ボタンなどで使う）。 */
	private static JButton styledButton(String text, Color color) {
		JButton b = new JButton(text);
		b.setFont(new Font("Meiryo", Font.BOLD, 13));
		b.setForeground(Color.WHITE);
		b.setBackground(color);
		b.setOpaque(true);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setBorder(new EmptyBorder(8, 18, 8, 18));
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return b;
	}

	/** 下部バーの、小さく控えめなボタン（「このサイトについて」「管理者ログイン」など）。 */
	private static JButton smallLinkButton(String text) {
		JButton b = new JButton(text);
		b.setFont(new Font("Meiryo", Font.PLAIN, 11));
		b.setForeground(PURPLE_DARK);
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return b;
	}

	/**
	 * 検索結果・年別一覧などで使う、1件ぶんの「カード」。
	 * 白い背景、薄い枠線、少し余白をつけて、見やすくしている。
	 */
	private static JPanel resultCard(String title, JButton actionButton) {
		JPanel row = new JPanel(new BorderLayout(10, 0));
		row.setBackground(Color.WHITE);
		row.setBorder(new CompoundBorder(new EmptyBorder(0, 0, 8, 0),
				new CompoundBorder(new MatteBorder(1, 1, 1, 1, CARD_BORDER), new EmptyBorder(10, 14, 10, 14))));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 28));

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(new Font("Meiryo", Font.BOLD, 13));
		titleLabel.setForeground(INK);
		row.add(titleLabel, BorderLayout.CENTER);

		actionButton.setFont(new Font("Meiryo", Font.PLAIN, 12));
		actionButton.setForeground(Color.WHITE);
		actionButton.setBackground(GOLD);
		actionButton.setOpaque(true);
		actionButton.setBorderPainted(false);
		actionButton.setFocusPainted(false);
		actionButton.setBorder(new EmptyBorder(6, 14, 6, 14));
		actionButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.add(actionButton, BorderLayout.EAST);

		return row;
	}

	/**
	 * 「検索」ボタンが押されたときの処理。
	 * 作品名だけでなく、主題歌・CD情報のすべての項目を検索対象にし、
	 * 半角／全角どちらの数字で入力しても、両方の表記にヒットするようにしている。
	 */
	private static void runSearch(Connection connection, JFrame frame, JTextField searchBox, JPanel resultPanel) {
		String keyword = searchBox.getText();

		try {
			String kwHalf = "%" + toHalfWidthDigits(keyword) + "%";
			String kwFull = "%" + toFullWidthDigits(keyword) + "%";

			String[] songCols = { "曲名", "作詞者", "作曲者", "編曲者", "歌唱者", "備考", "区分_OP1等" };
			String[] cdCols = { "CDタイトル", "収録曲", "発売会社", "品番", "備考" };

			StringBuilder songCond = new StringBuilder();
			for (String col : songCols) {
				if (songCond.length() > 0)
					songCond.append(" OR ");
				songCond.append(col).append(" LIKE ? OR ").append(col).append(" LIKE ?");
			}
			StringBuilder cdCond = new StringBuilder();
			for (String col : cdCols) {
				if (cdCond.length() > 0)
					cdCond.append(" OR ");
				cdCond.append(col).append(" LIKE ? OR ").append(col).append(" LIKE ?");
			}

			String sql = "SELECT * FROM works WHERE (" + "作品名 LIKE ? OR 作品名 LIKE ? OR "
					+ "id IN (SELECT work_id FROM songData WHERE " + songCond + ") OR "
					+ "id IN (SELECT work_id FROM cdData WHERE " + cdCond + ")"
					+ ") AND deleted = 0 ORDER BY 放映開始日";

			PreparedStatement ps = connection.prepareStatement(sql);
			int idx = 1;
			ps.setString(idx++, kwHalf);
			ps.setString(idx++, kwFull);
			for (int i = 0; i < songCols.length; i++) {
				ps.setString(idx++, kwHalf);
				ps.setString(idx++, kwFull);
			}
			for (int i = 0; i < cdCols.length; i++) {
				ps.setString(idx++, kwHalf);
				ps.setString(idx++, kwFull);
			}

			ResultSet rs = ps.executeQuery();

			resultPanel.removeAll(); // 前回の検索結果をクリアする

			while (rs.next()) {
				int workId = rs.getInt("id");
				String title = rs.getString("作品名") + " <放映>" + rs.getString("放映開始日") + "～" + rs.getString("放映終了日");

				JButton detailButton = new JButton("楽曲詳細");
				detailButton.addActionListener(ev -> openDetailFrame(connection, frame, workId, title));

				resultPanel.add(resultCard(title, detailButton));
			}

			resultPanel.revalidate(); // 画面を更新する
			resultPanel.repaint();

		} catch (SQLException ex) {
			resultPanel.removeAll();
			JLabel errLabel = new JLabel("エラーが発生しました：" + ex.getMessage());
			errLabel.setForeground(Color.RED);
			resultPanel.add(errLabel);
			resultPanel.revalidate();
			resultPanel.repaint();
		}
	}

	/**
	 * 「このサイトについて」ウィンドウを開く。
	 * 「作成意図」「TVオリジナルの定義」「データについて」「注意事項」を、この順番で表示する
	 * （AboutContentに書かれている文章と順番を、そのまま使う）。
	 */
	private static void openAboutWindow() {
		JFrame aboutFrame = new JFrame("このサイトについて");
		aboutFrame.setSize(560, 520);

		String html = "<html><body style='font-family:sans-serif;font-size:11px;padding:10px;'>" + AboutContent.BODY_HTML
				+ "</body></html>";
		JEditorPane pane = new JEditorPane("text/html", html);
		pane.setEditable(false);
		pane.setCaretPosition(0); // 表示したときに、一番上（作成意図）から見えるようにする

		aboutFrame.add(new JScrollPane(pane));
		aboutFrame.setVisible(true);
	}

	/**
	 * 「◯◯年代」ボタンが押されたときの処理。
	 * startYear〜endYearの年ボタンを並べたウィンドウを開く。
	 * 年ボタンを押すと、その年の作品一覧（＋楽曲詳細ボタン）が表示される。
	 */
	private static void openEraWindow(Connection connection, JFrame topFrame, int startYear, int endYear, String eraLabel) {
		JFrame yearFrame = new JFrame(eraLabel);
		yearFrame.setSize(320, 220);
		yearFrame.getContentPane().setBackground(CREAM);
		yearFrame.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));

		for (int year = startYear; year <= endYear; year++) {
			final int y = year; // その年専用の、動かない箱
			JButton yearButton = styledButton(y + "年", PURPLE);
			yearButton.addActionListener(yv -> openYearResultFrame(connection, topFrame, y));
			yearFrame.add(yearButton);
		}

		yearFrame.setVisible(true);
	}

	/** 指定した年の作品一覧ウィンドウを開く（該当する作品が無ければ「作成中」と表示）。 */
	private static void openYearResultFrame(Connection connection, JFrame topFrame, int year) {
		JFrame yearResultFrame = new JFrame(year + "年の作品一覧");
		yearResultFrame.setSize(460, 360);

		JPanel yearResultPanel = new JPanel();
		yearResultPanel.setLayout(new BoxLayout(yearResultPanel, BoxLayout.Y_AXIS));
		yearResultPanel.setBackground(Color.WHITE);
		yearResultPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		try {
			PreparedStatement psY = connection
					.prepareStatement("SELECT * FROM works WHERE 放映開始日 LIKE ? AND deleted = 0 ORDER BY 放映開始日");
			psY.setString(1, year + "%");
			ResultSet rsY = psY.executeQuery();

			boolean found = false;

			while (rsY.next()) {
				found = true;
				int workId = rsY.getInt("id");
				String title = rsY.getString("作品名");

				JButton yDetailButton = new JButton("楽曲詳細");
				yDetailButton.addActionListener(ev2 -> openDetailFrame(connection, topFrame, workId, title));

				yearResultPanel.add(resultCard(title, yDetailButton));
			}

			if (!found) {
				yearResultPanel.add(new JLabel("作成中"));
			}
		} catch (SQLException ex) {
			yearResultPanel.add(new JLabel("エラー：" + ex.getMessage()));
		}

		yearResultFrame.add(new JScrollPane(yearResultPanel));
		yearResultFrame.setVisible(true);
	}

	/**
	 * 「楽曲詳細」ウィンドウを開く。主題歌の情報（区分・作詞・作曲・歌唱者・備考）を表示し、
	 * 「音源詳細」ボタンから、さらにCD情報のウィンドウへ進める。
	 * 検索結果一覧・年別一覧、どちらから呼ばれても同じ動きになるよう、共通化している。
	 */
	private static void openDetailFrame(Connection connection, JFrame topFrame, int workId, String title) {
		JFrame detailFrame = new JFrame(title + " の楽曲詳細");
		detailFrame.setSize(500, 400);

		JTextArea detailArea = new JTextArea();
		detailArea.setEditable(false);
		JScrollPane detailScroll = new JScrollPane(detailArea);

		JButton backButton = new JButton("戻る");
		JButton topButton = new JButton("TOP");
		JPanel navPanel = new JPanel();
		navPanel.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		navPanel.add(backButton);
		navPanel.add(topButton);
		backButton.addActionListener(bv -> detailFrame.dispose());
		topButton.addActionListener(tv -> {
			detailFrame.dispose();
			topFrame.toFront();
		});

		JButton mediaButton = new JButton("音源詳細");

		JPanel detailPanel = new JPanel();
		detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
		detailPanel.add(navPanel);
		detailPanel.add(detailScroll);
		detailPanel.add(mediaButton);
		detailFrame.add(detailPanel);

		StringBuilder detail = new StringBuilder();
		try {
			PreparedStatement ps2 = connection.prepareStatement("SELECT * FROM songData WHERE work_id = ?");
			ps2.setInt(1, workId);
			ResultSet rs2 = ps2.executeQuery();
			while (rs2.next()) {
				detail.append("　曲名：" + rs2.getString("区分_OP1等") + "　" + rs2.getString("曲名")).append("\n");
				detail.append("　　作詞者：" + rs2.getString("作詞者")).append("\n");
				detail.append("　　作曲者：" + rs2.getString("作曲者")).append("\n");
				detail.append("　　歌唱者：" + rs2.getString("歌唱者")).append("\n");
				detail.append("　　備考：" + rs2.getString("備考")).append("\n");
			}
		} catch (SQLException ex) {
			detail.append("エラー：" + ex.getMessage());
		}
		detailArea.setText(detail.toString());

		mediaButton.addActionListener(mv -> openMediaFrame(connection, topFrame, detailFrame, workId, title));

		detailFrame.setVisible(true);
	}

	/**
	 * 「音源詳細」ウィンドウを開く。CD・音源の情報（品番・発売会社・収録曲・備考）と、
	 * Amazonで検索するボタンを、CDごとに1件ずつ表示する。
	 */
	private static void openMediaFrame(Connection connection, JFrame topFrame, JFrame detailFrame, int workId, String title) {
		JFrame mediaFrame = new JFrame(title + " の音源詳細");
		mediaFrame.setSize(500, 400);

		JButton backButton2 = new JButton("戻る");
		JButton topButton2 = new JButton("TOP");
		JPanel navPanel2 = new JPanel();
		navPanel2.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		navPanel2.add(backButton2);
		navPanel2.add(topButton2);
		backButton2.addActionListener(bv -> mediaFrame.dispose());
		topButton2.addActionListener(tv -> {
			mediaFrame.dispose();
			detailFrame.dispose();
			topFrame.toFront();
		});

		JPanel mediaPanel = new JPanel();
		mediaPanel.setLayout(new BoxLayout(mediaPanel, BoxLayout.Y_AXIS));
		mediaPanel.add(navPanel2);

		JPanel mediaListPanel = new JPanel();
		mediaListPanel.setLayout(new BoxLayout(mediaListPanel, BoxLayout.Y_AXIS));
		mediaPanel.add(new JScrollPane(mediaListPanel));
		mediaFrame.add(mediaPanel);

		try {
			PreparedStatement ps3 = connection.prepareStatement("SELECT * FROM cdData WHERE work_id = ?");
			ps3.setInt(1, workId);
			ResultSet rs3 = ps3.executeQuery();
			while (rs3.next()) {
				String tracks = rs3.getString("収録曲").replace("\n", "、");
				String url = buildAmazonUrl(rs3.getString("品番"), rs3.getString("CDタイトル"));

				// 1行目：CDタイトル＋Amazonボタン
				JPanel topLine = new JPanel();
				topLine.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0));

				JLabel titleLabel2 = new JLabel(rs3.getString("CDタイトル"));
				JButton amazonButton = new JButton("Amazon");
				amazonButton.addActionListener(av -> {
					try {
						Desktop.getDesktop().browse(new URI(url));
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				});
				topLine.add(titleLabel2);
				topLine.add(amazonButton);
				topLine.setMaximumSize(topLine.getPreferredSize());
				topLine.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

				// 2行目以降：品番・発売会社・収録曲・備考
				JLabel infoLabel = new JLabel("<html>　品番：" + rs3.getString("品番") + "<br>　発売会社：" + rs3.getString("発売会社")
						+ "<br>　収録曲：" + tracks + "</html>" + "<br>　備考：" + rs3.getString("備考"));
				infoLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

				JPanel cdRow = new JPanel();
				cdRow.setLayout(new BoxLayout(cdRow, BoxLayout.Y_AXIS));
				cdRow.add(topLine);
				cdRow.add(infoLabel);
				cdRow.setMaximumSize(cdRow.getPreferredSize());
				cdRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

				mediaListPanel.add(cdRow);
			}
		} catch (SQLException ex) {
			mediaListPanel.add(new JLabel("エラー：" + ex.getMessage()));
		}

		mediaFrame.setVisible(true);
	}

	// ==================== 文字・文字列を整える小さな部品たち ====================

	/** 全角数字（０-９）を半角数字（0-9）に変える。 */
	static String toHalfWidthDigits(String s) {
		if (s == null)
			return null;
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c >= '０' && c <= '９')
				sb.append((char) (c - '０' + '0'));
			else
				sb.append(c);
		}
		return sb.toString();
	}

	/** 半角数字（0-9）を全角数字（０-９）に変える。 */
	static String toFullWidthDigits(String s) {
		if (s == null)
			return null;
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c >= '0' && c <= '9')
				sb.append((char) (c - '0' + '０'));
			else
				sb.append(c);
		}
		return sb.toString();
	}

	/** CDタイトルから（　）内の注記などを取り除き、Amazon検索用に短くする。 */
	private static String cleanForAmazonSearch(String title) {
		if (title == null)
			return "";
		return title.replaceAll("（[^）]*）", "").replaceAll("\\([^)]*\\)", "").replace("～", " ").replace("・", " ")
				.replace("･", " ").trim();
	}

	/** 品番＋CDタイトルから、Amazonの検索結果ページへのURLを組み立てる（現在はCDタイトルのみで検索）。 */
	static String buildAmazonUrl(String hinban, String cdTitle) {
		try {
			String query = cleanForAmazonSearch(cdTitle);
			return "https://www.amazon.co.jp/s?k=" + java.net.URLEncoder.encode(query.trim(), "UTF-8");
		} catch (Exception e) {
			return "https://www.amazon.co.jp";
		}
	}
}
