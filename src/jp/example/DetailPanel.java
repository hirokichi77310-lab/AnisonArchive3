package jp.example;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * 「曲・音源（詳細ページ）」タブの画面。
 * 詳細ページ（主題歌の表とレコード・CDの一覧がのっているページ）のOCR結果から、
 * 主題歌（songData）と音源（cdData）の候補を作り、表で直してから、選んだ作品にDBへ登録する。
 *
 * ・候補の作成は OcrDetailParser が行う（文字と座標から、表の列や音源の区切りを推定する）。
 * ・行を選ぶと、画像がその行の位置へ移動する。
 * ・1つのページに作品が2つ以上あるときは、画像でShift＋ドラッグして範囲を選び、「選んだ範囲から候補を作る」を使う。
 * ・注意メモのある行は黄色く表示する。OCRで読めなかった所は空欄にして、理由をメモに書いてある。
 */
public class DetailPanel extends JPanel {
	private static final long serialVersionUID = 1L;

	// ---- DBに使うSQL ----
	static final String SQL_WORKS = "SELECT id, 作品名, 放映開始日, 放映終了日 FROM works WHERE deleted = 0 ORDER BY 放映開始日, 作品名";
	static final String SQL_SONG_EXISTS = "SELECT COUNT(*) FROM songData WHERE work_id = ? AND 曲名 = ?";
	static final String SQL_CD_EXISTS = "SELECT COUNT(*) FROM cdData WHERE work_id = ? AND CDタイトル = ? AND 品番 = ?";
	static final String SQL_SONG_INSERT = "INSERT INTO songData (作品名, 放映開始日, 放映終了日, work_id, 区分_OP1等, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	static final String SQL_CD_INSERT = "INSERT INTO cdData (作品名, 放映開始日, 放映終了日, work_id, CDタイトル, 収録曲, 購入URL, 発売会社, 品番, 備考) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	static final String SQL_SONG_UPDATE = "UPDATE songData SET 区分_OP1等 = ?, 作詞者 = ?, 作曲者 = ?, 編曲者 = ?, 歌唱者 = ?, 備考 = ? WHERE work_id = ? AND 曲名 = ?";
	static final String SQL_CD_UPDATE = "UPDATE cdData SET 収録曲 = ?, 発売会社 = ?, 備考 = ? WHERE work_id = ? AND CDタイトル = ? AND 品番 = ?";
	static final String SQL_SONG_BY_WORK = "SELECT 区分_OP1等, 曲名, 作詞者, 作曲者, 編曲者, 歌唱者, 備考 FROM songData WHERE work_id = ?";
	static final String SQL_CD_BY_WORK = "SELECT CDタイトル, 収録曲, 発売会社, 品番, 備考 FROM cdData WHERE work_id = ?";

	// 曲の表の列
	private static final String[] SONG_COLS = { "取込", "区分", "曲名", "作詞者", "作曲者", "編曲者", "歌唱者", "備考", "注意メモ" };
	private static final int S_INC = 0, S_KUBUN = 1, S_TITLE = 2, S_LYR = 3, S_COMP = 4, S_ARR = 5, S_SING = 6, S_NOTE = 7,
			S_MEMO = 8;
	// 音源の表の列
	private static final String[] CD_COLS = { "取込", "CDタイトル", "収録曲", "発売会社", "品番", "備考", "注意メモ" };
	private static final int C_INC = 0, C_TITLE = 1, C_TRACKS = 2, C_COMPANY = 3, C_HINBAN = 4, C_NOTE = 5, C_MEMO = 6;

	/** 登録先の作品の選択肢。 */
	static class WorkItem {
		final int id;
		final String title;
		final String start;
		final String end;

		WorkItem(int id, String title, String start, String end) {
			this.id = id;
			this.title = title;
			this.start = start;
			this.end = end;
		}

		@Override
		public String toString() {
			return id == 0 ? title : title + "（" + start + "〜" + end + "）";
		}
	}

	private final JFrame owner;
	private final ImagePanel imagePanel;

	private final JComboBox<WorkItem> workBox = new JComboBox<>();
	private final JTextArea statusLabel = new JTextArea(3, 40); // 状態の表示（幅に合わせて折り返す）

	private final DefaultTableModel songModel = newModel(SONG_COLS, S_MEMO);
	private final JTable songTable = new TipTable(songModel);
	private final List<Rectangle> songSources = new ArrayList<>();

	private final DefaultTableModel cdModel = newModel(CD_COLS, C_MEMO);
	private final JTable cdTable = new TipTable(cdModel);
	private final List<Rectangle> cdSources = new ArrayList<>();

	private OcrJson.Data ocr; // 座標つきのOCR結果（無ければnull）
	private String yearHint = "";
	private WorkItem lastGuess; // buildCore()が最後に推測した登録先（build()がメッセージに使う。1回限りの受け渡し用）
	// 登録済みデータを、どの作品ぶんはもう読み込んだか（別の作品を見てから戻ってきても、二重に読み込まないため）
	private int shownWorkId = -1; // 今、表に出ている内容がどの作品のものか（切り替えを検知するため）
	private boolean filling = false; // 候補を作っている最中（この間の変更は、手直しとは数えない）
	private boolean dirty = false; // 表を手で直した、または行を足した・消した

	public DetailPanel(JFrame owner, ImagePanel imagePanel) {
		super(new BorderLayout());
		this.owner = owner;
		this.imagePanel = imagePanel;

		// ---- 上：登録先の作品と、候補を作るボタン ----
		JButton refreshButton = new JButton("作品一覧を更新");
		JButton pageButton = new JButton("ページ全体から候補を作る");
		JButton areaButton = new JButton("選んだ範囲から候補を作る");
		JButton clearAreaButton = new JButton("範囲の選択を解除");
		JButton clearAllButton = new JButton("表をクリア");
		refreshButton.setToolTipText("DBの作品一覧を読み込み直します");
		pageButton.setToolTipText("このページに作品が1つだけのときに使います。候補は、今の表に追加されます（前の作品の行は消えません）");
		areaButton.setToolTipText("画像をShift＋ドラッグ（右ドラッグ）して範囲を選び、その範囲の作品だけを解析します。"
				+ "1つのページに作品が複数あるときは、作品ごとにこれを繰り返すと、表に行が追加されていきます");
		clearAllButton.setToolTipText("曲・音源の表を、全部空にします（新しいページを、まっさらな状態から解析したいときに使います）");
		refreshButton.addActionListener(e -> loadWorks());
		pageButton.addActionListener(e -> buildFromPage());
		areaButton.addActionListener(e -> buildFromSelection());
		clearAreaButton.addActionListener(e -> imagePanel.clearSelection());
		clearAllButton.addActionListener(e -> clearAll());

		workBox.setPrototypeDisplayValue(new WorkItem(0, "アルプスの少女ハイジ（1974/01/06〜1974/12/29）　　　　　　", "", ""));
		workBox.setToolTipText("作品を選ぶと、その作品にすでに登録されている曲・音源があれば、表に呼び出します");
		workBox.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof WorkItem) {
				loadExistingForWork((WorkItem) e.getItem());
			}
		});
		statusLabel.setEditable(false);
		statusLabel.setLineWrap(true);
		statusLabel.setWrapStyleWord(true);
		statusLabel.setOpaque(false);
		statusLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 4, 8));
		JPanel row1 = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
		row1.add(new JLabel("登録先の作品："));
		row1.add(workBox);
		row1.add(refreshButton);
		JButton registerButton = new JButton("この内容をDBに登録");
		registerButton.addActionListener(e -> register());
		JPanel row2 = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
		row2.add(pageButton);
		row2.add(areaButton);
		row2.add(clearAreaButton);
		row2.add(clearAllButton);
		row2.add(registerButton);
		JPanel row3 = new JPanel(new BorderLayout());
		row3.add(statusLabel, BorderLayout.CENTER);
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(row1);
		top.add(row2);
		top.add(row3);
		add(top, BorderLayout.NORTH);

		// ---- 中：曲の表と、音源の表 ----
		setupTable(songTable, new int[] { 45, 90, 150, 100, 110, 110, 190, 130, 420 }, songModel, songSources, S_MEMO);
		JComboBox<String> kubunBox = new JComboBox<>(
				new String[] { "OP", "ED", "OP・ED", "挿入歌", "イメージソング", "(OP)", "(ED)", "" });
		kubunBox.setEditable(true);
		songTable.getColumnModel().getColumn(S_KUBUN).setCellEditor(new DefaultCellEditor(kubunBox));

		setupTable(cdTable, new int[] { 45, 300, 300, 80, 110, 300, 420 }, cdModel, cdSources, C_MEMO);

		JButton addSong = new JButton("曲を追加");
		JButton delSong = new JButton("選んだ曲を削除");
		addSong.addActionListener(e -> addRow(songModel, songSources, new Object[] { Boolean.TRUE, "", "", "", "", "", "", "", "" }));
		delSong.addActionListener(e -> deleteSelected(songTable, songModel, songSources));
		JButton lyricistAll = new JButton("作詞者をまとめて入れる");
		lyricistAll.setToolTipText("表の作詞者が「〃（上と同じ）」のとき、名前を1回入力して、空欄の全ての曲に入れます");
		lyricistAll.addActionListener(e -> fillLyricist());
		JButton addCd = new JButton("音源を追加");
		JButton delCd = new JButton("選んだ音源を削除");
		addCd.addActionListener(e -> addRow(cdModel, cdSources, new Object[] { Boolean.TRUE, "", "", "", "", "", "" }));
		delCd.addActionListener(e -> deleteSelected(cdTable, cdModel, cdSources));

		JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				section("主題歌（songData）", songTable, addSong, delSong, lyricistAll),
				section("音源（cdData）", cdTable, addCd, delCd, null));
		center.setResizeWeight(0.5);
		add(center, BorderLayout.CENTER);


		resetWorkBox();
		setStatus("OCR結果（.json）を読み込むと、ここに曲と音源の候補が出ます。");
	}

	// ==================== 外から使う操作 ====================

	/** 新しいOCR結果を受け取る。前のページの候補は消す。 */
	public void setOcrData(OcrJson.Data data) {
		this.ocr = data;
		clearTables();
		if (data == null) {
			setStatus("座標の入った.jsonが無いため、詳細ページの候補は作れません（同じ名前の.jsonを同じフォルダに置いてください）。");
		} else {
			setStatus("「ページ全体から候補を作る」か、範囲を選んで「選んだ範囲から候補を作る」を押してください。");
		}
	}

	public boolean hasOcrData() {
		return ocr != null;
	}

	public void setYearHint(String yearHint) {
		this.yearHint = yearHint == null ? "" : yearHint;
	}

	/** 手直しした内容が残っているときは、消してよいかを聞く。消してよい（または手直しなし）ならtrue。 */
	public boolean confirmDiscard() {
		if (!dirty) {
			return true;
		}
		int answer = JOptionPane.showConfirmDialog(owner, "「曲・音源」の表に、まだ登録していない手直しがあります。消してよろしいですか？", "確認",
				JOptionPane.YES_NO_OPTION);
		return answer == JOptionPane.YES_OPTION;
	}

	/**
	 * ページ全体から候補を作る。作れたらtrue。
	 * （1ページに複数の作品がのっているときは、作品ごとに範囲を選んで
	 * 「選んだ範囲から候補を作る」を繰り返してください。自動で複数作品に分ける機能は、
	 * 誤って分けてしまう場合があったため、今は用意していません。）
	 */
	public boolean buildFromPage() {
		return build(null);
	}

	/** 画像で選んだ範囲から候補を作る。 */
	public boolean buildFromSelection() {
		Rectangle sel = imagePanel.getSelection();
		if (sel == null) {
			JOptionPane.showMessageDialog(owner, "先に、画像をShift＋ドラッグ（または右ドラッグ）して、作品の範囲を選んでください。");
			return false;
		}
		return build(scaleRegionToOcr(sel));
	}

	/** 画像の座標（今表示している画像）を、OCRの座標に直す（画像の大きさが違うときだけ変わる）。 */
	private Rectangle scaleRegionToOcr(Rectangle sel) {
		int w = imagePanel.getImageWidth();
		int h = imagePanel.getImageHeight();
		if (ocr == null || ocr.imageWidth <= 0 || ocr.imageHeight <= 0 || w <= 0 || h <= 0
				|| (w == ocr.imageWidth && h == ocr.imageHeight)) {
			return sel;
		}
		double sx = (double) ocr.imageWidth / w;
		double sy = (double) ocr.imageHeight / h;
		return new Rectangle((int) Math.round(sel.x * sx), (int) Math.round(sel.y * sy), (int) Math.round(sel.width * sx),
				(int) Math.round(sel.height * sy));
	}

	/**
	 * 1つの範囲（1作品ぶん）を解析して、表に追加する。表への反映まで行い、結果（Result）を返す。
	 * エラー時はダイアログを出してnullを返す。状態表示（setStatus）は、呼び出し側（1件用・複数件用）に任せる。
	 */
	private OcrDetailParser.Result buildCore(Rectangle regionInOcr) {
		OcrDetailParser.Result res;
		try {
			res = OcrDetailParser.parse(ocr.lines, regionInOcr, yearHint);
		} catch (RuntimeException ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(owner, "候補の作成中にエラーが起きました（" + ex + "）。このOCR結果（.json）を、開発者に見せてください。");
			return null;
		}

		// 登録先の作品を、見出しの文字から推測する
		loadWorks();
		WorkItem guess = guessWork(res.workTitleGuess, res.pageYear);
		if (guess != null) {
			workBox.setSelectedItem(guess);
			for (OcrDetailParser.CdRow c : res.cds) {
				String t = OcrDetailParser.fixTitleTypos(c.title, guess.title);
				String n = OcrDetailParser.fixTitleTypos(c.note, guess.title);
				if (!t.equals(c.title) || !n.equals(c.note)) {
					c.title = t;
					c.note = n;
					c.memo += (c.memo.isEmpty() ? "" : " / ") + "作品名の表記を、DBの作品名に合わせて直しました";
				}
			}
		}
		fill(res);
		lastGuess = guess; // build()が、推測結果のメッセージを組み立てるために使う（この呼び出し分だけ有効）
		return res;
	}

	private boolean build(Rectangle regionInOcr) {
		if (ocr == null) {
			JOptionPane.showMessageDialog(owner, "座標の入った.jsonを読み込んでから、もう一度押してください。");
			return false;
		}
		OcrDetailParser.Result res = buildCore(regionInOcr);
		if (res == null) {
			return false;
		}
		WorkItem guess = lastGuess;

		StringBuilder sb = new StringBuilder(" 曲 " + res.songs.size() + "件・音源 " + res.cds.size() + "件を、表に追加しました（前の作品の行は消えません）。");
		if (guess != null) {
			sb.append("登録先の作品を「").append(guess.title).append("」と推測しました（違えば選び直してください）。");
		} else if (!res.workTitleGuess.isEmpty()) {
			sb.append("見出し「").append(res.workTitleGuess).append("」に合う作品がDBに見つかりません。登録先を選んでください。");
		} else {
			sb.append("見出しが読み取れないため、登録先の作品を選んでください。");
		}
		for (String m : res.messages) {
			sb.append("　※").append(m);
		}
		setStatus(sb.toString());
		return !res.songs.isEmpty() || !res.cds.isEmpty();
	}

	/** 見出しの文字（欠けていることがある）に合う作品を、DBの作品から探す。年が分かれば、その年の作品を優先する。 */
	private WorkItem guessWork(String titleGuess, int pageYear) {
		String key = titleGuess == null ? "" : titleGuess.replaceAll("[\\s　]", "");
		if (key.length() < 3) {
			return null;
		}
		// 見出しを含む（または、見出しに含まれる）作品のうち、同じ年で、作品名の長さが近いものを選ぶ
		WorkItem best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int i = 1; i < workBox.getItemCount(); i++) {
			WorkItem w = workBox.getItemAt(i);
			String t = w.title.replaceAll("[\\s　]", "");
			if (t.contains(key) || key.contains(t)) {
				boolean sameYear = pageYear > 0 && w.start != null && w.start.startsWith(String.valueOf(pageYear));
				int score = (sameYear ? 0 : 1000) + Math.abs(t.length() - key.length());
				if (score < bestScore) {
					best = w;
					bestScore = score;
				}
			}
		}
		return best;
	}

	// ==================== 表の準備 ====================

	private static DefaultTableModel newModel(String[] cols, int memoCol) {
		return new DefaultTableModel(cols, 0) {
			private static final long serialVersionUID = 1L;

			@Override
			public Class<?> getColumnClass(int c) {
				return c == 0 ? Boolean.class : String.class;
			}

			@Override
			public boolean isCellEditable(int r, int c) {
				return c != memoCol;
			}
		};
	}

	private void setupTable(JTable table, int[] widths, DefaultTableModel model, List<Rectangle> sources, int memoCol) {
		// 列は、画面の幅に合わせて自動で縮む（右に隠れる列が出ないように）。長い文字は、マウスを乗せると全文が出る
		table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
		table.setRowHeight(24);
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		table.setDefaultRenderer(String.class, new MemoRenderer(model, memoCol));
		// 行を選ぶと、画像がその行の位置へ移動する
		table.getSelectionModel().addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) {
				return;
			}
			int viewRow = table.getSelectedRow();
			if (viewRow < 0 || !imagePanel.hasImage()) {
				return;
			}
			int r = table.convertRowIndexToModel(viewRow);
			Rectangle box = r < sources.size() ? sources.get(r) : null;
			if (box == null) {
				imagePanel.clearHighlight();
			} else {
				imagePanel.focusOn(scaleToImage(box));
			}
		});
		// 手で直したら、「未登録の手直しあり」にする
		model.addTableModelListener(e -> {
			if (!filling && e.getType() == TableModelEvent.UPDATE) {
				dirty = true;
			}
		});
		addSelectAllHeader(table, model);
	}

	/**
	 * 「取込」列（0列目）の見出しに、チェック欄を付ける。
	 * 見出しを押すと、今チェックが全部入っていれば全部外し、1つでも外れていれば全部に入れる
	 * （チェックを入れる／外す、のどちらかに、1クリックで揃えられる）。
	 */
	private void addSelectAllHeader(JTable table, DefaultTableModel model) {
		JTableHeader header = table.getTableHeader();
		JCheckBox headerCheck = new JCheckBox();
		headerCheck.setHorizontalAlignment(JCheckBox.CENTER);
		headerCheck.setToolTipText("クリックで、全部チェック／全部解除を切り替えます");

		table.getColumnModel().getColumn(0).setHeaderRenderer((t, value, isSelected, hasFocus, row, column) -> {
			headerCheck.setSelected(allChecked(model));
			return headerCheck;
		});

		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int viewCol = header.columnAtPoint(e.getPoint());
				if (viewCol < 0 || table.convertColumnIndexToModel(viewCol) != 0 || model.getRowCount() == 0) {
					return;
				}
				boolean newValue = !allChecked(model);
				filling = true; // 1件ずつの変更として扱わず、まとめての操作にする（dirtyは下でまとめて立てる）
				for (int r = 0; r < model.getRowCount(); r++) {
					model.setValueAt(newValue, r, 0);
				}
				filling = false;
				dirty = true;
				header.repaint();
			}
		});
	}

	private static boolean allChecked(DefaultTableModel model) {
		if (model.getRowCount() == 0) {
			return false;
		}
		for (int r = 0; r < model.getRowCount(); r++) {
			if (!Boolean.TRUE.equals(model.getValueAt(r, 0))) {
				return false;
			}
		}
		return true;
	}

	private JPanel section(String title, JTable table, JButton add, JButton del, JButton extra) {
		JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
		bar.add(new JLabel(title + "　"));
		bar.add(add);
		bar.add(del);
		if (extra != null) {
			bar.add(extra);
		}
		JPanel p = new JPanel(new BorderLayout());
		p.add(bar, BorderLayout.NORTH);
		p.add(new JScrollPane(table), BorderLayout.CENTER);
		return p;
	}

	/** OCRの座標を作ったときの画像と、今表示している画像の大きさが違うときは、比率で直す。 */
	private Rectangle scaleToImage(Rectangle box) {
		int w = imagePanel.getImageWidth();
		int h = imagePanel.getImageHeight();
		if (ocr == null || ocr.imageWidth <= 0 || ocr.imageHeight <= 0 || (w == ocr.imageWidth && h == ocr.imageHeight)) {
			return box;
		}
		double sx = (double) w / ocr.imageWidth;
		double sy = (double) h / ocr.imageHeight;
		return new Rectangle((int) Math.round(box.x * sx), (int) Math.round(box.y * sy), (int) Math.round(box.width * sx),
				(int) Math.round(box.height * sy));
	}

	private void addRow(DefaultTableModel model, List<Rectangle> sources, Object[] row) {
		model.addRow(row);
		sources.add(null);
		dirty = true;
	}

	private void deleteSelected(JTable table, DefaultTableModel model, List<Rectangle> sources) {
		int[] rows = table.getSelectedRows();
		if (rows.length == 0) {
			JOptionPane.showMessageDialog(owner, "削除する行を選んでください");
			return;
		}
		for (int i = rows.length - 1; i >= 0; i--) {
			int r = table.convertRowIndexToModel(rows[i]);
			model.removeRow(r);
			if (r < sources.size()) {
				sources.remove(r);
			}
		}
		dirty = true;
	}

	private void clearTables() {
		filling = true;
		songModel.setRowCount(0);
		cdModel.setRowCount(0);
		songSources.clear();
		cdSources.clear();
		filling = false;
		dirty = false;
		shownWorkId = -1; // 表をまっさらにしたので、次に作品を選んだときは、登録済みデータを読み込み直してよい
		imagePanel.clearHighlight();
	}

	/**
	 * 解析結果を、表に追加する（前に入っていた、別の作品の行は消さない）。
	 * 1つのページに複数の作品がのっているとき、「選んだ範囲から候補を作る」を作品ごとに繰り返せば、
	 * 全部の作品の行が、同じ表の中に並んでいく。
	 * 取り違えを防ぐため、新しく追加した行だけ「取込」にチェックを入れ、それより前の行のチェックは外す
	 * （登録は、作品ごとに「チェックを入れる→登録先を選ぶ→DBに登録」を繰り返す想定）。
	 */
	private void fill(OcrDetailParser.Result res) {
		filling = true;
		for (int r = 0; r < songModel.getRowCount(); r++) {
			songModel.setValueAt(Boolean.FALSE, r, S_INC);
		}
		for (int r = 0; r < cdModel.getRowCount(); r++) {
			cdModel.setValueAt(Boolean.FALSE, r, C_INC);
		}
		for (OcrDetailParser.SongRow s : res.songs) {
			songModel.addRow(new Object[] { Boolean.TRUE, s.kubun, s.title, s.lyricist, s.composer, s.arranger, s.singer, s.note,
					s.memo });
			songSources.add(s.source);
		}
		for (OcrDetailParser.CdRow c : res.cds) {
			cdModel.addRow(new Object[] { Boolean.TRUE, c.title, c.tracks, c.company, c.hinban, c.note, c.memo });
			cdSources.add(c.source);
		}
		filling = false;
		dirty = songModel.getRowCount() > 0 || cdModel.getRowCount() > 0;
		imagePanel.clearHighlight();
	}

	/** 表の内容を、全部消す（新しいページを、まっさらな状態から解析したいときに使う）。 */
	private void clearAll() {
		if (!confirmDiscard()) {
			return;
		}
		clearTables();
		setStatus("表をクリアしました。「ページ全体から候補を作る」か、範囲を選んで「選んだ範囲から候補を作る」を押してください。");
	}

	/** 状態の表示（長い文は、折り返して全部見えるようにする）。 */
	private void setStatus(String text) {
		statusLabel.setText(text.trim());
		statusLabel.setCaretPosition(0);
	}

	// ==================== DB ====================

	private void resetWorkBox() {
		workBox.removeAllItems();
		workBox.addItem(new WorkItem(0, "（登録先の作品を選んでください）", "", ""));
	}

	/** DBの作品一覧を読み込んで、選択肢にする（DBにつなげなくても、画面は使える）。 */
	void loadWorks() {
		WorkItem selected = (WorkItem) workBox.getSelectedItem();
		resetWorkBox();
		try (Connection c = Database.connect();
				PreparedStatement ps = c.prepareStatement(SQL_WORKS);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				workBox.addItem(new WorkItem(rs.getInt(1), rs.getString(2), nz(rs.getString(3)), nz(rs.getString(4))));
			}
		} catch (SQLException | RuntimeException | LinkageError ex) {
			setStatus("DBに接続できないため、作品一覧を読み込めません（" + ex.getMessage() + "）。登録するときは、DBにつながる状態にしてください。");
		}
		if (selected != null && selected.id != 0) {
			for (int i = 1; i < workBox.getItemCount(); i++) {
				if (workBox.getItemAt(i).id == selected.id) {
					workBox.setSelectedIndex(i);
					break;
				}
			}
		}
	}

	/**
	 * 「登録先の作品」で作品を選んだときに呼ばれる。表の中身を、その作品のものだけに入れ替える
	 * （他の作品の行は残さない）。すでに登録されている曲・音源があれば、そのまま呼び出して表示する
	 * （チェックは外しておく＝登録済みなので、間違って二重登録しないため）。
	 * 画像でさらに範囲を選んで「選んだ範囲から候補を作る」を押せば、ここに新しい候補が追加される。
	 * 同じ作品を選び直しただけのときは、何もしない（表の内容はそのまま）。
	 */
	private void loadExistingForWork(WorkItem work) {
		if (work == null || work.id == 0 || work.id == shownWorkId) {
			return;
		}
		if (!confirmDiscard()) {
			// 元の作品の選択に戻す（勝手に切り替わったままにしない）
			for (int i = 0; i < workBox.getItemCount(); i++) {
				if (workBox.getItemAt(i).id == shownWorkId) {
					workBox.setSelectedIndex(i);
					break;
				}
			}
			return;
		}
		clearTables();
		shownWorkId = work.id;

		List<Object[]> songRows = new ArrayList<>();
		List<Object[]> cdRows = new ArrayList<>();
		try (Connection c = Database.connect()) {
			try (PreparedStatement ps = c.prepareStatement(SQL_SONG_BY_WORK)) {
				ps.setInt(1, work.id);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						songRows.add(new Object[] { Boolean.FALSE, nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)),
								nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6)), nz(rs.getString(7)), "DBに登録済み" });
					}
				}
			}
			try (PreparedStatement ps = c.prepareStatement(SQL_CD_BY_WORK)) {
				ps.setInt(1, work.id);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						cdRows.add(new Object[] { Boolean.FALSE, nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)),
								nz(rs.getString(4)), nz(rs.getString(5)), "DBに登録済み" });
					}
				}
			}
		} catch (SQLException | RuntimeException | LinkageError ex) {
			setStatus("「" + work.title + "」の登録済みデータを読み込めませんでした（" + ex.getMessage() + "）。");
			return;
		}

		if (songRows.isEmpty() && cdRows.isEmpty()) {
			setStatus("「" + work.title + "」には、まだ登録されている曲・音源がありません。");
			return;
		}

		filling = true;
		for (Object[] row : songRows) {
			songModel.addRow(row);
			songSources.add(null); // DBから読んだ行は、画像の位置と結び付いていない
		}
		for (Object[] row : cdRows) {
			cdModel.addRow(row);
			cdSources.add(null);
		}
		filling = false;
		// 登録済みのデータをそのまま映しているだけなので、「未登録の手直しがある」扱いにはしない
		// （dirtyはfalseのまま＝このあと別の作品に切り替えても、確認なしで切り替えられる）
		setStatus("「" + work.title + "」に登録済みの、曲 " + songRows.size() + "件・音源 " + cdRows.size()
				+ "件を表に呼び出しました（登録済みなので、チェックは外してあります）。画像で範囲を選んで候補を作ると、この続きに追加されます。");
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private String cell(DefaultTableModel m, int r, int c) {
		Object v = m.getValueAt(r, c);
		return v == null ? "" : v.toString().trim();
	}

	private boolean checked(DefaultTableModel m, int r) {
		return Boolean.TRUE.equals(m.getValueAt(r, 0));
	}

	/** 選んだ作品に、チェックの入った曲・音源を登録する。 */
	private void register() {
		WorkItem work = (WorkItem) workBox.getSelectedItem();
		if (work == null || work.id == 0) {
			JOptionPane.showMessageDialog(owner, "登録先の作品を選んでください。\n（一覧ページから、先に作品を登録しておく必要があります）");
			return;
		}
		List<Integer> songRows = new ArrayList<>();
		List<Integer> cdRows = new ArrayList<>();
		StringBuilder problems = new StringBuilder();
		for (int r = 0; r < songModel.getRowCount(); r++) {
			if (checked(songModel, r)) {
				songRows.add(r);
				if (cell(songModel, r, S_TITLE).isEmpty()) {
					problems.append("・曲の").append(r + 1).append("行目：曲名が空です\n");
				}
			}
		}
		for (int r = 0; r < cdModel.getRowCount(); r++) {
			if (checked(cdModel, r)) {
				cdRows.add(r);
				if (cell(cdModel, r, C_TITLE).isEmpty()) {
					problems.append("・音源の").append(r + 1).append("行目：CDタイトルが空です\n");
				}
			}
		}
		if (songRows.isEmpty() && cdRows.isEmpty()) {
			JOptionPane.showMessageDialog(owner, "登録する行にチェックを入れてください。");
			return;
		}
		if (problems.length() > 0) {
			JOptionPane.showMessageDialog(owner, "次の行を直してください。\n\n" + problems);
			return;
		}
		int answer = JOptionPane.showConfirmDialog(owner,
				"「" + work.title + "」に、曲 " + songRows.size() + "件・音源 " + cdRows.size() + "件を登録します。\n"
						+ "（すでに同じ曲名・同じ品番のものがあれば、新規ではなく、その内容を今の表の内容で上書きします）\nよろしいですか？",
				"確認", JOptionPane.YES_NO_OPTION);
		if (answer != JOptionPane.YES_OPTION) {
			return;
		}

		int songAdded = 0, songUpdated = 0, cdAdded = 0, cdUpdated = 0;
		try (Connection c = Database.connect()) {
			for (int r : songRows) {
				String title = cell(songModel, r, S_TITLE);
				String kubun = cell(songModel, r, S_KUBUN);
				String lyricist = cell(songModel, r, S_LYR);
				String composer = cell(songModel, r, S_COMP);
				String arranger = cell(songModel, r, S_ARR);
				String singer = cell(songModel, r, S_SING);
				String note = cell(songModel, r, S_NOTE);
				if (exists(c, SQL_SONG_EXISTS, work.id, title, null)) {
					try (PreparedStatement ps = c.prepareStatement(SQL_SONG_UPDATE)) {
						ps.setString(1, kubun);
						ps.setString(2, lyricist);
						ps.setString(3, composer);
						ps.setString(4, arranger);
						ps.setString(5, singer);
						ps.setString(6, note);
						ps.setInt(7, work.id);
						ps.setString(8, title);
						ps.execute();
					}
					songUpdated++;
					songModel.setValueAt(Boolean.FALSE, r, S_INC);
					continue;
				}
				try (PreparedStatement ps = c.prepareStatement(SQL_SONG_INSERT)) {
					ps.setString(1, work.title);
					ps.setString(2, work.start);
					ps.setString(3, work.end);
					ps.setInt(4, work.id);
					ps.setString(5, kubun);
					ps.setString(6, title);
					ps.setString(7, lyricist);
					ps.setString(8, composer);
					ps.setString(9, arranger);
					ps.setString(10, singer);
					ps.setString(11, note);
					ps.execute();
				}
				songAdded++;
				songModel.setValueAt(Boolean.FALSE, r, S_INC);
			}
			for (int r : cdRows) {
				String title = cell(cdModel, r, C_TITLE);
				String hinban = cell(cdModel, r, C_HINBAN);
				String tracks = cell(cdModel, r, C_TRACKS);
				String company = cell(cdModel, r, C_COMPANY);
				String note = cell(cdModel, r, C_NOTE);
				if (exists(c, SQL_CD_EXISTS, work.id, title, hinban)) {
					try (PreparedStatement ps = c.prepareStatement(SQL_CD_UPDATE)) {
						ps.setString(1, tracks);
						ps.setString(2, company);
						ps.setString(3, note);
						ps.setInt(4, work.id);
						ps.setString(5, title);
						ps.setString(6, hinban);
						ps.execute();
					}
					cdUpdated++;
					cdModel.setValueAt(Boolean.FALSE, r, C_INC);
					continue;
				}
				try (PreparedStatement ps = c.prepareStatement(SQL_CD_INSERT)) {
					ps.setString(1, work.title);
					ps.setString(2, work.start);
					ps.setString(3, work.end);
					ps.setInt(4, work.id);
					ps.setString(5, title);
					ps.setString(6, tracks);
					ps.setString(7, ""); // 購入URLは空（Amazonのボタンは、タイトルから検索URLを作る）
					ps.setString(8, company);
					ps.setString(9, hinban);
					ps.setString(10, note);
					ps.execute();
				}
				cdAdded++;
				cdModel.setValueAt(Boolean.FALSE, r, C_INC);
			}
		} catch (SQLException | RuntimeException | LinkageError ex) {
			JOptionPane.showMessageDialog(owner, "DBのエラー：" + ex.getMessage() + "\n（ここまでに登録できた分は、DBに入っています）");
			return;
		}
		dirty = false;
		String msg = "登録しました：曲　新規 " + songAdded + "件・上書き " + songUpdated + "件　／　音源　新規 " + cdAdded + "件・上書き " + cdUpdated + "件";
		setStatus(msg);
		JOptionPane.showMessageDialog(owner, msg);
	}

	/** 同じ作品に、同じ曲名（または、同じCDタイトルと品番）がすでにあるか。 */
	private boolean exists(Connection c, String sql, int workId, String a, String b) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setInt(1, workId);
			ps.setString(2, a);
			if (b != null) {
				ps.setString(3, b);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}

	/** 作詞者を1回入力して、空欄の全ての曲に入れる（表の作詞者が「〃」のときに使う）。 */
	private void fillLyricist() {
		if (songModel.getRowCount() == 0) {
			JOptionPane.showMessageDialog(owner, "曲の行がありません。");
			return;
		}
		String first = "";
		for (int r = 0; r < songModel.getRowCount() && first.isEmpty(); r++) {
			first = cell(songModel, r, S_LYR);
		}
		Object input = JOptionPane.showInputDialog(owner, "作詞者の名前（空欄の曲すべてに入れます）", first);
		if (input == null || input.toString().trim().isEmpty()) {
			return;
		}
		for (int r = 0; r < songModel.getRowCount(); r++) {
			if (cell(songModel, r, S_LYR).isEmpty()) {
				songModel.setValueAt(input.toString().trim(), r, S_LYR);
			}
		}
	}

	/** セルにマウスを乗せると、その全文を出す表（列が狭くて、文字が切れて見えるときのため）。 */
	@SuppressWarnings("serial")
	private static class TipTable extends JTable {
		TipTable(DefaultTableModel model) {
			super(model);
		}

		@Override
		public String getToolTipText(MouseEvent e) {
			int r = rowAtPoint(e.getPoint());
			int c = columnAtPoint(e.getPoint());
			if (r < 0 || c < 0) {
				return null;
			}
			Object v = getValueAt(r, c);
			if (v == null || v.toString().isEmpty() || v instanceof Boolean) {
				return null;
			}
			String safe = v.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
			return "<html><body style='width:380px'>" + safe + "</body></html>";
		}
	}

	/** 注意メモのある行を、薄い黄色にする。 */
	@SuppressWarnings("serial")
	private static class MemoRenderer extends DefaultTableCellRenderer {
		private final DefaultTableModel model;
		private final int memoCol;

		MemoRenderer(DefaultTableModel model, int memoCol) {
			this.model = model;
			this.memoCol = memoCol;
		}

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r, int c) {
			Component comp = super.getTableCellRendererComponent(t, value, selected, focus, r, c);
			if (!selected) {
				Object memo = r < model.getRowCount() ? model.getValueAt(r, memoCol) : null;
				comp.setBackground(memo != null && !memo.toString().isEmpty() ? new Color(255, 248, 205) : Color.WHITE);
			}
			return comp;
		}
	}
}
