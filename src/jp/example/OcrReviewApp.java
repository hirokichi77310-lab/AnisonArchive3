package jp.example;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.prefs.Preferences;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * 資料の取り込み（OCR結果の確認と登録）画面。
 *
 * 流れ：①画像を選ぶ → ②OCR結果（NDLOCR-Liteが出力した.txt／.json）を読み込む
 *      → ③表に出た候補のうち、黄色・赤の行を画像と見比べて直す → ④選んだ行をDB（works）に登録。
 * 表の行を選ぶと、画像の該当する位置へ自動で移動し、黄色い枠で示す（座標の入った.jsonを読んだとき）。
 * 右側は3つのタブ：
 *  ・作品の候補：一覧ページ（作品名と放映期間）を、作品名・日付に分解した表
 *  ・曲・音源（詳細ページ）：詳細ページ（主題歌の表とレコード・CDの一覧）を、曲と音源の候補にした表（DetailPanel）
 *  ・OCR全行：読み取った全ての行と信頼度
 * 「作品名……日付」の形の行が無い資料は詳細ページとみなし、「曲・音源」の候補を自動で作る。
 * OCRは間違えることがあるため、「AI／OCRが候補を出し、人が確認して登録する」形にしている。
 * （この画面で登録できるのは、目次ページから分かる「作品名と放映期間」まで。曲やCDの情報は今後の課題。）
 */
public class OcrReviewApp {

	private static final String DATE_REGEX = "^\\d{4}/\\d{2}/\\d{2}$";
	private static final String[] COLUMNS = { "取込", "種別", "作品名", "放映開始日", "放映終了日", "状態", "注意メモ", "元のOCR行" };
	private static final int COL_INCLUDE = 0, COL_KIND = 1, COL_TITLE = 2, COL_START = 3, COL_END = 4, COL_STATUS = 5,
			COL_NOTE = 6, COL_RAW = 7;

	final JFrame frame = new JFrame("資料の取り込み（OCR結果の確認と登録）― 詳細ページ対応版");
	private final ImagePanel imagePanel = new ImagePanel();
	private final JTextField yearField = new JTextField(5);
	private final JLabel statusLabel = new JLabel(
			" ①画像を選ぶ ②OCR結果(.txt/.json)を読み込む ③行を選ぶと画像がその位置へ動くので、黄色・赤の行を見比べて直す ④登録する行にチェックを入れて「DBに登録」");

	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override
		public Class<?> getColumnClass(int c) {
			return c == COL_INCLUDE ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c == COL_INCLUDE || c == COL_TITLE || c == COL_START || c == COL_END;
		}
	};
	private final JTable table = new JTable(model);

	// 「OCR全行」タブ：読み取った全ての行（文字・信頼度）を、そのまま並べる
	private final DefaultTableModel lineModel = new DefaultTableModel(new String[] { "No.", "OCRの文字", "信頼度" }, 0) {
		@Override
		public boolean isCellEditable(int r, int c) {
			return false;
		}
	};
	private final JTable lineTable = new JTable(lineModel);
	private final List<Rectangle> lineBoxes = new ArrayList<>(); // 全行の、画像の中での位置（無ければnull）
	private final JTabbedPane tabs = new JTabbedPane();
	private static final int TAB_WORKS = 0, TAB_DETAIL = 1, TAB_LINES = 2; // タブの番号
	private final DetailPanel detailPanel; // 詳細ページ用の画面

	private File imageFile; // 選んだ画像
	private boolean tableEdited = false; // 表の作品名・日付を、手で直したか
	private OcrJson.Data ocrJson; // 座標つきのOCR結果（.jsonが無ければnull）
	private final List<Rectangle> rowBoxes = new ArrayList<>(); // 表の各行の、画像の中での位置（無ければnull）
	private int boxSourceWidth; // 上の座標を作ったときの画像の幅・高さ（実際の画像と違えば、比率で直す）
	private int boxSourceHeight;
	private String mainText; // メインのOCR結果（NDLOCR-Liteなど）
	private String checkText; // 照合用のOCR結果（Googleドキュメントなど・任意）

	// 一度選んだフォルダを覚えておき、次にファイルを選ぶときの初期位置にする（次回アプリを起動しても覚えています）
	private static final Preferences LAST_DIR_PREF = Preferences.userNodeForPackage(OcrReviewApp.class);
	private static final String LAST_DIR_KEY = "lastOpenFolder";

	/** 「前回開いていた場所」から始まり、表示方法が「詳細」になったファイル選択画面を作る。 */
	private static JFileChooser newFileChooser() {
		JFileChooser chooser = new JFileChooser();
		String last = LAST_DIR_PREF.get(LAST_DIR_KEY, null);
		if (last != null && new File(last).isDirectory()) {
			chooser.setCurrentDirectory(new File(last));
		}
		// 一覧の見た目を「詳細」にする（更新日時やサイズが見えるほうが、ファイルを探しやすいため）
		javax.swing.Action details = chooser.getActionMap().get("viewTypeDetails");
		if (details != null) {
			details.actionPerformed(null);
		}
		return chooser;
	}

	/** ファイルを選んだあと、そのフォルダを「次回開く場所」として覚えておく。 */
	private static void rememberFolder(File selectedFile) {
		File folder = selectedFile.isDirectory() ? selectedFile : selectedFile.getParentFile();
		if (folder != null) {
			LAST_DIR_PREF.put(LAST_DIR_KEY, folder.getAbsolutePath());
		}
	}

	public OcrReviewApp() {
		// 画面より大きくならないようにする（画面が小さいPCや、表示の拡大設定をしているPCで、右や下が隠れないように）
		Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		frame.setSize(Math.min(1400, usable.width), Math.min(820, usable.height));
		frame.setLocation(usable.x, usable.y);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		// ---- 上のボタン列 ----
		JButton chooseImageButton = new JButton("画像を選ぶ");
		JButton openImageButton = new JButton("画像を大きく開く");
		JButton loadTextButton = new JButton("OCR結果(.txt/.json)を読み込む");
		JButton pasteCheckButton = new JButton("照合用OCR結果を貼り付け（任意）");
		JButton parseButton = new JButton("解析する");
		JButton registerButton = new JButton("選んだ行をDBに登録");

		JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
		top.add(chooseImageButton);
		top.add(openImageButton);
		top.add(loadTextButton);
		top.add(pasteCheckButton);
		top.add(new JLabel("　資料の年："));
		top.add(yearField);
		top.add(parseButton);
		top.add(registerButton);

		chooseImageButton.setToolTipText("資料の画像を選んで、左に表示します（ホイールで拡大縮小、ドラッグで移動）");
		openImageButton.setToolTipText("画像を、Windowsの画像ビューアーで大きく開きます");
		loadTextButton.setToolTipText("<html>NDLOCR-Liteが出力した.txt か .json を読み込み、そのまま解析して表に出します。<br>"
				+ "同じ名前の.jsonがあれば座標も読み、表の行を選ぶと画像がその位置へ動きます。</html>");
		pasteCheckButton.setToolTipText("別のOCR（Googleドキュメントなど）の文字を貼ると、食い違う行に印が付きます");
		yearField.setToolTipText("開始日の年に使います。空のままなら、OCR結果の中の「1974」のような行から探します");
		parseButton.setToolTipText("<html>読み込んだOCR結果の<b>文字</b>を、作品名・開始日・終了日に分解して、表を作り直します。<br>"
				+ "（画像そのものは読みません。表で直した内容は消えます）</html>");
		registerButton.setToolTipText("チェックを入れた行を、DB（works）に登録します");

		chooseImageButton.addActionListener(e -> chooseImage());
		openImageButton.addActionListener(e -> openImageExternally());
		loadTextButton.addActionListener(e -> chooseAndLoadText());
		pasteCheckButton.addActionListener(e -> pasteCheckText());
		parseButton.addActionListener(e -> requestParse());
		registerButton.addActionListener(e -> registerSelected());

		// ---- 表 ----
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(24);
		int[] widths = { 45, 55, 230, 95, 95, 85, 520, 330 };
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		table.setDefaultRenderer(String.class, new StatusRenderer());

		// 表の行を選んだら（クリックでも、↑↓キーでも）、画像の該当する位置へ移動する
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showSelectedRowInImage();
			}
		});

		// 作品名・日付を手で直したら、状態を自動で更新する
		model.addTableModelListener(e -> {
			if (e.getType() != TableModelEvent.UPDATE) {
				return;
			}
			int r = e.getFirstRow();
			int c = e.getColumn();
			if (r < 0 || r >= model.getRowCount() || c < COL_TITLE || c > COL_END) {
				return;
			}
			tableEdited = true;
			boolean ok = isRegistrable(cell(r, COL_TITLE), cell(r, COL_START), cell(r, COL_END));
			model.setValueAt(ok ? "修正済み" : "日付要入力", r, COL_STATUS);
		});

		// ---- 左：画像／右：表 ----
		final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, imagePanel, tabs);
		split.setResizeWeight(0.36);
		// 境目（画像と表の間）を、どちらにも自由に動かせるようにする
		imagePanel.setMinimumSize(new Dimension(120, 120));
		tabs.setMinimumSize(new Dimension(320, 200));
		split.setContinuousLayout(true); // ドラッグ中も、画像がリアルタイムで広がる
		split.setOneTouchExpandable(true); // 境目の小さな三角で、片方を一気に広げられる
		detailPanel = new DetailPanel(frame, imagePanel);
		tabs.addTab("作品の候補", new JScrollPane(table));
		tabs.addTab("曲・音源（詳細ページ）", detailPanel);
		tabs.addTab("OCR全行", new JScrollPane(lineTable));

		// 「OCR全行」タブの見た目と動き
		lineTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		lineTable.setRowHeight(24);
		int[] lineWidths = { 50, 620, 70 };
		for (int i = 0; i < lineWidths.length; i++) {
			lineTable.getColumnModel().getColumn(i).setPreferredWidth(lineWidths[i]);
		}
		lineTable.setDefaultRenderer(Object.class, new ConfidenceRenderer());
		lineTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showSelectedLineInImage();
			}
		});
		// 画像は約3分の1、右の表は約3分の2の幅で始める（ウィンドウを開いた直後に決める）
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				split.setDividerLocation(0.36);
			}
		});

		frame.add(top, BorderLayout.NORTH);
		frame.add(split, BorderLayout.CENTER);
		frame.add(statusLabel, BorderLayout.SOUTH);
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> new OcrReviewApp().frame.setVisible(true));
	}

	// ==================== ①画像 ====================

	private void chooseImage() {
		JFileChooser chooser = newFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("画像ファイル", "jpg", "jpeg", "png", "bmp", "gif"));
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			rememberFolder(chooser.getSelectedFile());
			showImage(chooser.getSelectedFile());
		}
	}

	void showImage(File file) {
		try {
			BufferedImage img = ImageIO.read(file);
			if (img == null) {
				JOptionPane.showMessageDialog(frame, "画像として読み込めませんでした：" + file.getName());
				return;
			}
			imageFile = file;
			imagePanel.setImage(img);
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(frame, "画像を読み込めませんでした：" + ex.getMessage());
		}
	}

	/** 画像を、Windowsの標準の画像ビューアーで大きく開く（文字を見比べるため）。 */
	private void openImageExternally() {
		if (imageFile == null) {
			JOptionPane.showMessageDialog(frame, "先に画像を選んでください");
			return;
		}
		try {
			Desktop.getDesktop().open(imageFile);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "画像を開けませんでした：" + ex.getMessage());
		}
	}

	// ==================== ②OCR結果の読み込み ====================

	private void chooseAndLoadText() {
		JFileChooser chooser = newFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("OCR結果（.txt / .json）", "txt", "json"));
		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		rememberFolder(chooser.getSelectedFile());
		try {
			loadOcrFile(chooser.getSelectedFile());
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(frame, "ファイルを読み込めませんでした：" + ex.getMessage());
		}
	}

	/**
	 * OCR結果のファイルを読み込む。
	 * ・.json：文字と、各行の座標の両方を読む（NDLOCR-Liteが出力するJSON）
	 * ・.txt ：文字を読む。同じ名前の.jsonが同じフォルダにあれば、座標もそこから読む
	 * 座標があると、表の行を選んだときに画像の該当する位置へ移動できる。
	 */
	void loadOcrFile(File file) throws IOException {
		if (!detailPanel.confirmDiscard()) {
			return; // 「曲・音源」の手直しを消したくないときは、読み込まない
		}
		String name = file.getName();
		String text;
		File jsonFile = null;
		ocrJson = null;

		if (name.toLowerCase().endsWith(".json")) {
			jsonFile = file;
			ocrJson = OcrJson.load(file);
			StringBuilder sb = new StringBuilder();
			for (OcrJson.Line line : ocrJson.lines) {
				sb.append(line.text).append("\n");
			}
			text = sb.toString();
		} else {
			text = readText(file);
			int dot = name.lastIndexOf('.');
			File sibling = new File(file.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".json");
			if (sibling.isFile()) {
				try {
					ocrJson = OcrJson.load(sibling);
					jsonFile = sibling;
				} catch (IOException ex) {
					ocrJson = null; // JSONが読めなくても、文字だけで続ける
				}
			}
		}

		if (ocrJson != null) {
			autoLoadImage(jsonFile, ocrJson);
		}
		detailPanel.setOcrData(ocrJson);
		loadMainText(text);
	}

	/**
	 * 一覧ページとして読めなかった（＝詳細ページかもしれない）のに、座標の.jsonが無いとき、.jsonを選んでもらう。
	 * 詳細ページの曲・音源の候補は、文字だけでなく座標（どこにあるか）が無いと作れないため。
	 */
	private void offerJsonForDetail() {
		int answer = JOptionPane.showConfirmDialog(frame,
				"この資料は、一覧ページ（作品名と放映期間）として読めませんでした。\n"
						+ "詳細ページ（曲・作詞者・作曲者・編曲者・歌唱者・音源）の候補を作るには、座標の入った.json\n"
						+ "（NDLOCR-Liteが.txtと一緒に出力するファイル）が必要です。\n\n.jsonファイルを選びますか？",
				"詳細ページの候補を作るには", JOptionPane.YES_NO_OPTION);
		if (answer != JOptionPane.YES_OPTION) {
			return;
		}
		JFileChooser chooser = newFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("座標つきのOCR結果（.json）", "json"));
		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		rememberFolder(chooser.getSelectedFile());
		try {
			File jsonFile = chooser.getSelectedFile();
			ocrJson = OcrJson.load(jsonFile);
			autoLoadImage(jsonFile, ocrJson);
			fillLineTable();
			detailPanel.setOcrData(ocrJson);
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(frame, "JSONを読み込めませんでした：" + ex.getMessage());
		}
	}

	/** JSONには「どの画像をOCRしたか」が書いてあるので、その画像が見つかれば、自動で表示する。 */
	private void autoLoadImage(File jsonFile, OcrJson.Data data) {
		if (data.imageName.isEmpty() || (imageFile != null && imageFile.getName().equals(data.imageName))) {
			return;
		}
		File[] candidates = { new File(data.imagePath), new File(jsonFile.getParentFile(), data.imageName) };
		for (File c : candidates) {
			if (c.isFile()) {
				showImage(c);
				return;
			}
		}
	}

	/** まずUTF-8で読み、文字化けする形式（Windowsのメモ帳のANSIなど）ならMS932（Shift_JIS系）で読み直す。 */
	private static String readText(File file) throws IOException {
		try {
			return Files.readString(file.toPath());
		} catch (MalformedInputException e) {
			return Files.readString(file.toPath(), Charset.forName("MS932"));
		}
	}

	/** OCR結果を受け取って、そのまま解析まで進める。 */
	void loadMainText(String text) {
		mainText = text;
		fillLineTable();
		requestParse();
	}

	/** 照合用（別のOCRの結果）を貼り付けるための小さな入力ウィンドウ。 */
	private void pasteCheckText() {
		JTextArea area = new JTextArea(22, 60);
		if (checkText != null) {
			area.setText(checkText);
		}
		int answer = JOptionPane.showConfirmDialog(frame, new JScrollPane(area),
				"照合用OCR結果を貼り付け（Googleドキュメントなどの文字）", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (answer == JOptionPane.OK_OPTION) {
			applyCheckText(area.getText());
		}
	}

	void applyCheckText(String text) {
		checkText = text;
		if (mainText != null) {
			requestParse(); // 読み込み済みなら、すぐ照合をやり直す
		}
	}

	// ==================== ③解析して表に出す ====================

	/**
	 * 表を作り直す前に、表で手直しした内容が消えてしまってよいかを確認する。
	 * （手直ししていなければ、確認なしでそのまま作り直す）
	 */
	void requestParse() {
		if (tableEdited) {
			int answer = JOptionPane.showConfirmDialog(frame, "表を作り直します。表で直した内容は消えますが、よろしいですか？", "確認",
					JOptionPane.YES_NO_OPTION);
			if (answer != JOptionPane.YES_OPTION) {
				return;
			}
		}
		runParse();
	}

	void runParse() {
		if (mainText == null || mainText.isBlank()) {
			JOptionPane.showMessageDialog(frame, "先にOCR結果(.txt)を読み込んでください");
			return;
		}
		OcrLineParser.Result r = OcrLineParser.parse(mainText, yearField.getText(), checkText);
		if (r.year.isEmpty()) {
			// 年が分からないと、開始日を決められない。一覧ページではないとみなして、表は空にする
			model.setRowCount(0);
			tableEdited = false;
			rowBoxes.clear();
			imagePanel.clearHighlight();
			yearField.setBackground(new Color(255, 248, 205));
			String note = " 年を読み取れませんでした。一覧ページなら「資料の年」欄（黄色）に入力して「解析する」を押してください。";
			if (!detailPanel.hasOcrData()) {
				offerJsonForDetail();
			}
			if (detailPanel.hasOcrData()) {
				detailPanel.setYearHint("");
				detailPanel.buildFromPage();
				tabs.setSelectedIndex(TAB_DETAIL);
				note += "詳細ページとして、「曲・音源」タブに候補を作りました。";
			} else {
				tabs.setSelectedIndex(TAB_LINES);
				note += "詳細ページなど年のない資料は、「OCR全行」タブで内容を確認できます（候補の自動作成には.jsonが必要です）。";
			}
			statusLabel.setText(note);
			return;
		}
		yearField.setBackground(Color.WHITE);
		yearField.setText(r.year);
		model.setRowCount(0);
		tableEdited = false;

		// 表の各行が、画像のどこにあるか（座標）を対応づける
		rowBoxes.clear();
		boxSourceWidth = 0;
		boxSourceHeight = 0;
		imagePanel.clearHighlight();
		if (ocrJson != null) {
			List<String> rawLines = new ArrayList<>();
			for (OcrLineParser.Row row : r.rows) {
				rawLines.add(row.raw);
			}
			rowBoxes.addAll(OcrJson.matchBoxes(rawLines, ocrJson.lines));
			boxSourceWidth = ocrJson.imageWidth;
			boxSourceHeight = ocrJson.imageHeight;
		}

		// DBに同じ作品（作品名＋開始日）が既にあるかを調べる（DBに接続できなくても、解析自体は続ける）
		String dbNote = "";
		Connection conn = null;
		try {
			conn = Database.connect();
		} catch (SQLException | RuntimeException | LinkageError ex) {
			// ドライバー（sqlite-jdbc）が読み込めない場合なども含め、DBの問題で表の表示を止めない
			dbNote = "　※DBに接続できないため、登録済みチェックはしていません";
		}

		int ok = 0, check = 0, need = 0, done = 0;
		for (OcrLineParser.Row row : r.rows) {
			if (conn != null && !row.start.isEmpty()) {
				try {
					if (existsInDb(conn, row.title, row.start)) {
						row.status = "登録済み";
						row.include = false;
						row.note = row.note.isEmpty() ? "同じ作品名・開始日がDBにあります" : row.note + " / 同じ作品名・開始日がDBにあります";
					}
				} catch (SQLException ignored) {
					// 1行の確認に失敗しても、表示は続ける
				}
			}
			switch (row.status) {
			case "OK" -> ok++;
			case "要確認" -> check++;
			case "登録済み" -> done++;
			default -> need++;
			}
			model.addRow(new Object[] { row.include, row.kind, row.title, row.start, row.end, row.status, row.note,
					row.raw });
		}
		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException ignored) {
				// 閉じるのに失敗しても影響なし
			}
		}

		String skipped = r.skipped.isEmpty() ? ""
				: "　読み飛ばした行：" + r.skipped.size() + "行（" + String.join(" ／ ", r.skipped.subList(0, Math.min(3, r.skipped.size())))
						+ "）";
		int linked = 0;
		for (Rectangle b : rowBoxes) {
			if (b != null) {
				linked++;
			}
		}
		String emptyHint = "";
		if (r.rows.isEmpty()) {
			// 「作品名……日付」の形の行が1つも無い＝詳細ページなど。「曲・音源」の候補を作る
			if (!detailPanel.hasOcrData()) {
				offerJsonForDetail();
			}
			if (detailPanel.hasOcrData()) {
				detailPanel.setYearHint(yearField.getText());
				detailPanel.buildFromPage();
				tabs.setSelectedIndex(TAB_DETAIL);
				emptyHint = "　※「作品名……日付」の形の行が無いため、詳細ページとして「曲・音源」タブに候補を作りました。";
			} else {
				tabs.setSelectedIndex(TAB_LINES);
				emptyHint = "　※「作品名……日付」の形の行が無いため、候補は0行です（詳細ページなど）。「OCR全行」タブに、読み取った全ての行を表示しています。";
			}
		} else {
			tabs.setSelectedIndex(TAB_WORKS);
		}
		String link = ocrJson == null ? (r.rows.isEmpty() ? "" : "　※座標の.jsonが無いため、行を選んでも画像は動きません")
				: "　画像と連動：" + linked + "/" + r.rows.size() + "行";
		statusLabel.setText(" 読み取り " + r.rows.size() + "行　OK " + ok + " ／ 要確認 " + check + " ／ 日付要入力 " + need + " ／ 登録済み "
				+ done + skipped + link + dbNote + (checkText == null ? "" : "　（照合用OCRあり）") + emptyHint);
	}

	/** 「OCR全行」タブに、読み取った全ての行を並べる（座標があれば、行を選ぶと画像がその位置へ動く）。 */
	private void fillLineTable() {
		lineModel.setRowCount(0);
		lineBoxes.clear();
		int no = 1;
		if (ocrJson != null) {
			for (OcrJson.Line line : ocrJson.lines) {
				lineModel.addRow(new Object[] { no++, line.text, line.confidence >= 0 ? String.format("%.2f", line.confidence) : "" });
				lineBoxes.add(line.rect);
			}
			boxSourceWidth = ocrJson.imageWidth;
			boxSourceHeight = ocrJson.imageHeight;
		} else if (mainText != null) {
			for (String text : mainText.split("\\R")) {
				if (!text.isBlank()) {
					lineModel.addRow(new Object[] { no++, text, "" });
					lineBoxes.add(null);
				}
			}
		}
	}

	/** 「OCR全行」タブで選んでいる行の位置へ、画像を移動する。 */
	private void showSelectedLineInImage() {
		int viewRow = lineTable.getSelectedRow();
		if (viewRow < 0 || !imagePanel.hasImage()) {
			return;
		}
		int r = lineTable.convertRowIndexToModel(viewRow);
		Rectangle box = r < lineBoxes.size() ? lineBoxes.get(r) : null;
		if (box == null) {
			imagePanel.clearHighlight();
			return;
		}
		imagePanel.focusOn(scaleToImage(box));
	}

	/** 表で選んでいる行の位置へ、画像を移動して、黄色い枠を付ける。 */
	private void showSelectedRowInImage() {
		int viewRow = table.getSelectedRow();
		if (viewRow < 0 || !imagePanel.hasImage()) {
			return;
		}
		int r = table.convertRowIndexToModel(viewRow);
		Rectangle box = r < rowBoxes.size() ? rowBoxes.get(r) : null;
		if (box == null) {
			imagePanel.clearHighlight(); // この行の位置は分からない
			return;
		}
		imagePanel.focusOn(scaleToImage(box));
	}

	/** 座標を作ったときの画像と、今表示している画像の大きさが違うときは、比率で直す。 */
	private Rectangle scaleToImage(Rectangle box) {
		int w = imagePanel.getImageWidth();
		int h = imagePanel.getImageHeight();
		if (boxSourceWidth <= 0 || boxSourceHeight <= 0 || (w == boxSourceWidth && h == boxSourceHeight)) {
			return box;
		}
		double sx = (double) w / boxSourceWidth;
		double sy = (double) h / boxSourceHeight;
		return new Rectangle((int) Math.round(box.x * sx), (int) Math.round(box.y * sy), (int) Math.round(box.width * sx),
				(int) Math.round(box.height * sy));
	}

	private boolean existsInDb(Connection conn, String title, String start) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM works WHERE 作品名 = ? AND 放映開始日 = ?")) {
			ps.setString(1, title);
			ps.setString(2, start);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}

	// ==================== ④DBに登録 ====================

	private void registerSelected() {
		List<Integer> targets = new ArrayList<>();
		for (int r = 0; r < model.getRowCount(); r++) {
			if (Boolean.TRUE.equals(model.getValueAt(r, COL_INCLUDE))) {
				targets.add(r);
			}
		}
		if (targets.isEmpty()) {
			JOptionPane.showMessageDialog(frame, "登録する行にチェックを入れてください");
			return;
		}

		// 先に全部の行を検査する（1つでもおかしければ、何も登録しない）
		StringBuilder problems = new StringBuilder();
		for (int r : targets) {
			if (!isRegistrable(cell(r, COL_TITLE), cell(r, COL_START), cell(r, COL_END))) {
				problems.append("・").append(r + 1).append("行目：").append(cell(r, COL_TITLE)).append("\n");
			}
		}
		if (problems.length() > 0) {
			JOptionPane.showMessageDialog(frame,
					"次の行は、作品名か日付（yyyy/MM/dd。終了日は「放送中」も可）を確認してください。\n\n" + problems);
			return;
		}

		int answer = JOptionPane.showConfirmDialog(frame, targets.size() + "件をDB（works）に登録します。よろしいですか？", "確認",
				JOptionPane.YES_NO_OPTION);
		if (answer != JOptionPane.YES_OPTION) {
			return;
		}

		try (Connection conn = Database.connect()) {
			int added = 0, skipped = 0;
			for (int r : targets) {
				String title = cell(r, COL_TITLE);
				String start = cell(r, COL_START);
				String end = cell(r, COL_END);
				if (existsInDb(conn, title, start)) {
					model.setValueAt("登録済み", r, COL_STATUS);
					skipped++;
				} else {
					try (PreparedStatement ps = conn
							.prepareStatement("INSERT INTO works (作品名, 放映開始日, 放映終了日) VALUES (?, ?, ?)")) {
						ps.setString(1, title);
						ps.setString(2, start);
						ps.setString(3, end);
						ps.execute();
					}
					model.setValueAt("登録しました", r, COL_STATUS);
					added++;
				}
				model.setValueAt(Boolean.FALSE, r, COL_INCLUDE);
			}
			JOptionPane.showMessageDialog(frame, added + "件を登録しました。" + (skipped > 0 ? "\n（すでにDBにあったため、" + skipped + "件は登録していません）" : ""));
		} catch (SQLException ex) {
			JOptionPane.showMessageDialog(frame, "DBのエラー：" + ex.getMessage());
		}
	}

	// ==================== 小さな部品たち ====================

	private String cell(int row, int col) {
		Object v = model.getValueAt(row, col);
		return v == null ? "" : v.toString().trim();
	}

	/** 登録してよい形か：作品名があり、開始日が yyyy/MM/dd、終了日が yyyy/MM/dd または「放送中」。 */
	private static boolean isRegistrable(String title, String start, String end) {
		return !title.isBlank() && start.matches(DATE_REGEX) && (end.matches(DATE_REGEX) || end.equals("放送中"));
	}

	/** 「OCR全行」の、信頼度の低い行に色を付ける（赤み＝特に低い、黄色＝やや低い）。読み間違いを探す目印になる。 */
	@SuppressWarnings("serial")
	private static class ConfidenceRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r,
				int c) {
			Component comp = super.getTableCellRendererComponent(t, value, selected, focus, r, c);
			if (!selected) {
				Color bg = Color.WHITE;
				try {
					double conf = Double.parseDouble(String.valueOf(t.getValueAt(r, 2)));
					bg = conf < 0.5 ? new Color(255, 224, 224) : conf < 0.75 ? new Color(255, 248, 205) : Color.WHITE;
				} catch (NumberFormatException ignored) {
					// 信頼度が無い（.txtだけのとき）は白のまま
				}
				comp.setBackground(bg);
			}
			return comp;
		}
	}

	/** 「状態」の列に応じて、行の背景色を変える（黄色＝要確認、赤＝日付要入力、など）。 */
	@SuppressWarnings("serial")
	private class StatusRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r,
				int c) {
			Component comp = super.getTableCellRendererComponent(t, value, selected, focus, r, c);
			if (!selected) {
				comp.setBackground(colorFor(String.valueOf(model.getValueAt(r, COL_STATUS))));
			}
			return comp;
		}

		private Color colorFor(String status) {
			return switch (status) {
			case "OK" -> new Color(234, 250, 241); // 薄い緑
			case "要確認" -> new Color(255, 248, 205); // 薄い黄
			case "日付要入力" -> new Color(255, 224, 224); // 薄い赤
			case "登録済み" -> new Color(238, 238, 238); // 灰色
			case "修正済み" -> new Color(230, 240, 255); // 薄い青
			case "登録しました" -> new Color(210, 240, 220); // 緑
			default -> Color.WHITE;
			};
		}
	}
}
