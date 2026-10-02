package jp.example;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * 画像を表示するための部品。
 * ・マウスのホイール：マウスの位置を中心に、拡大・縮小する
 * ・ドラッグ：見たい場所へ画像を動かす
 * ・ダブルクリック：画像全体が見える大きさに戻す
 * ・Shift＋ドラッグ（または右ボタンのドラッグ）：範囲を選ぶ（選んだ範囲は、詳細ページの解析などに使う）
 *
 * 大きな画像（スキャンした資料など）を小さく表示するときに文字がつぶれないよう、
 * あらかじめ半分・4分の1・8分の1…の大きさの画像を作っておき、表示する大きさに近いものを使う。
 */
public class ImagePanel extends JPanel {
	private static final long serialVersionUID = 1L;

	/** 画像を、どれだけ画面の外へ動かしてよいか（最低これだけの大きさは画面内に残す）。 */
	private static final int KEEP_VISIBLE = 80;
	private static final double MAX_SCALE = 8.0; // 最大800%
	/** 「この行へ移動」のとき、文字が読める最小の倍率と、拡大しすぎない最大の倍率。 */
	private static final double MIN_FOCUS_SCALE = 0.3;
	private static final double MAX_FOCUS_SCALE = 1.6;

	private List<BufferedImage> levels = new ArrayList<>(); // 0番目が元の大きさ、以降は半分ずつ
	private int imageWidth;
	private int imageHeight;

	private double scale = 1.0; // 元の画像に対する、表示の倍率
	private double offsetX = 0; // 画像の左上の、画面上の位置
	private double offsetY = 0;
	private boolean fitMode = true; // trueの間は、ウィンドウの大きさが変わっても「全体表示」を保つ
	private Rectangle highlight; // 印を付ける行の、画像の中での位置（なければnull）
	private Rectangle selection; // 選んだ範囲の、画像の中での位置（なければnull）
	private boolean selecting = false; // 範囲を選んでいる最中か
	private Point2D selectStart; // 範囲選択を始めた点（画像の座標）

	private Point dragStart;
	private double dragStartOffsetX;
	private double dragStartOffsetY;

	public ImagePanel() {
		setBackground(new Color(70, 70, 70));
		setOpaque(true);
		setPreferredSize(new Dimension(520, 700));

		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (!hasImage()) {
					return;
				}
				if (SwingUtilities.isRightMouseButton(e) || (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown())) {
					// 範囲選択の開始
					selecting = true;
					selectStart = toImage(e.getPoint());
					selection = null;
					setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
					repaint();
					return;
				}
				if (SwingUtilities.isLeftMouseButton(e)) {
					dragStart = e.getPoint();
					dragStartOffsetX = offsetX;
					dragStartOffsetY = offsetY;
					setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (selecting) {
					selection = rectBetween(selectStart, toImage(e.getPoint()));
					repaint();
					return;
				}
				if (dragStart == null) {
					return;
				}
				offsetX = dragStartOffsetX + (e.getX() - dragStart.x);
				offsetY = dragStartOffsetY + (e.getY() - dragStart.y);
				fitMode = false;
				clampOffsets();
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (selecting) {
					selecting = false;
					if (selection != null && (selection.width < 20 || selection.height < 20)) {
						selection = null; // 小さすぎる（クリックだけ）ときは、選択なし
					}
					repaint();
				}
				dragStart = null;
				updateCursor();
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && hasImage()) {
					fitToWindow();
					repaint();
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);

		// ホイールを奥に回す（マイナス）と拡大、手前に回す（プラス）と縮小
		addMouseWheelListener(e -> zoomAt(e.getX(), e.getY(), Math.pow(1.15, -e.getPreciseWheelRotation())));

		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (fitMode) {
					fitToWindow();
				} else {
					clampOffsets();
				}
				repaint();
			}
		});
	}

	public boolean hasImage() {
		return !levels.isEmpty();
	}

	/** 表示する画像を入れ替える。全体が見える大きさから始める。 */
	public void setImage(BufferedImage source) {
		levels = new ArrayList<>();
		highlight = null;
		selection = null;
		if (source != null) {
			imageWidth = source.getWidth();
			imageHeight = source.getHeight();

			// 描画が速い形式（INT_RGB）にそろえておく
			BufferedImage base = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = base.createGraphics();
			g.drawImage(source, 0, 0, null);
			g.dispose();
			levels.add(base);

			// 半分ずつ小さくした画像を作っておく（小さく表示するときに、文字がつぶれにくくなる）
			BufferedImage current = base;
			while (current.getWidth() > 400 && current.getHeight() > 400) {
				int w = Math.max(1, current.getWidth() / 2);
				int h = Math.max(1, current.getHeight() / 2);
				BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				Graphics2D g2 = next.createGraphics();
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g2.drawImage(current, 0, 0, w, h, null);
				g2.dispose();
				levels.add(next);
				current = next;
			}
		}
		fitToWindow();
		updateCursor();
		repaint();
	}

	/** 画像全体が、ちょうど画面に収まる大きさにする。 */
	public void fitToWindow() {
		if (!hasImage() || getWidth() <= 0 || getHeight() <= 0) {
			return;
		}
		scale = fitScale();
		offsetX = (getWidth() - imageWidth * scale) / 2;
		offsetY = (getHeight() - imageHeight * scale) / 2;
		fitMode = true;
	}

	/** 元の画像の幅・高さ（画像が無ければ0）。 */
	public int getImageWidth() {
		return hasImage() ? imageWidth : 0;
	}

	public int getImageHeight() {
		return hasImage() ? imageHeight : 0;
	}

	/** 印（黄色い枠）を消す。 */
	public void clearHighlight() {
		highlight = null;
		repaint();
	}

	/**
	 * 画像の中の四角形（OCRで読み取った1行など）に印を付け、その行が画面の真ん中に来るように動かす。
	 * ・今の倍率で、その行が画面の横幅に収まるなら、倍率は変えない（続けて何行も見るとき、拡大縮小がころころ変わらない）。
	 * ・収まらない、または小さすぎて読めない倍率なら、その行が読める大きさに変える。
	 * ・行が長すぎて画面に収まらないときは、行の左端（作品名の側）が見えるようにする。
	 */
	public void focusOn(Rectangle imageRect) {
		if (!hasImage() || getWidth() <= 0 || getHeight() <= 0 || imageRect == null) {
			return;
		}
		highlight = new Rectangle(imageRect);
		double panelW = getWidth();
		double panelH = getHeight();
		double padded = imageRect.width + 48; // 行の左右に少し余白をとる

		boolean fits = padded * scale <= panelW * 0.95;
		if (scale < MIN_FOCUS_SCALE || !fits) {
			double desired = Math.max(MIN_FOCUS_SCALE, Math.min(MAX_FOCUS_SCALE, panelW * 0.92 / padded));
			scale = Math.max(fitScale() * 0.5, Math.min(MAX_SCALE, desired));
		}

		double centerX = imageRect.x + imageRect.width / 2.0;
		double centerY = imageRect.y + imageRect.height / 2.0;
		if (imageRect.width * scale <= panelW * 0.95) {
			offsetX = panelW / 2 - centerX * scale;
		} else {
			offsetX = panelW * 0.03 - imageRect.x * scale;
		}
		offsetY = panelH / 2 - centerY * scale;
		fitMode = false;
		clampOffsets();
		repaint();
	}

	/** 画面上の点を、画像の中の座標に直す。 */
	private Point2D toImage(Point p) {
		return new Point2D.Double((p.x - offsetX) / scale, (p.y - offsetY) / scale);
	}

	/** 2点を角とする四角形（画像の外へはみ出さないようにする）。 */
	private Rectangle rectBetween(Point2D a, Point2D b) {
		int x1 = (int) Math.round(Math.max(0, Math.min(imageWidth, Math.min(a.getX(), b.getX()))));
		int y1 = (int) Math.round(Math.max(0, Math.min(imageHeight, Math.min(a.getY(), b.getY()))));
		int x2 = (int) Math.round(Math.max(0, Math.min(imageWidth, Math.max(a.getX(), b.getX()))));
		int y2 = (int) Math.round(Math.max(0, Math.min(imageHeight, Math.max(a.getY(), b.getY()))));
		return new Rectangle(x1, y1, x2 - x1, y2 - y1);
	}

	/** 選んだ範囲（画像の座標）。選んでいなければnull。 */
	public Rectangle getSelection() {
		return selection == null ? null : new Rectangle(selection);
	}

	/** 選んだ範囲を解除する。 */
	public void clearSelection() {
		selection = null;
		repaint();
	}

	private double fitScale() {
		return Math.min((double) getWidth() / imageWidth, (double) getHeight() / imageHeight);
	}

	/** (mx, my) の位置にある画像の点が、動かないように拡大・縮小する。 */
	void zoomAt(double mx, double my, double factor) {
		if (!hasImage()) {
			return;
		}
		double minScale = fitScale() * 0.5;
		double newScale = Math.max(minScale, Math.min(MAX_SCALE, scale * factor));
		double ratio = newScale / scale;
		offsetX = mx - (mx - offsetX) * ratio;
		offsetY = my - (my - offsetY) * ratio;
		scale = newScale;
		fitMode = false;
		clampOffsets();
		repaint();
	}

	/** 画像が、画面の外へ出きってしまわないように、位置を調整する。 */
	private void clampOffsets() {
		if (!hasImage()) {
			return;
		}
		double drawnW = imageWidth * scale;
		double drawnH = imageHeight * scale;
		double minX = KEEP_VISIBLE - drawnW;
		double maxX = getWidth() - KEEP_VISIBLE;
		double minY = KEEP_VISIBLE - drawnH;
		double maxY = getHeight() - KEEP_VISIBLE;
		offsetX = Math.max(Math.min(minX, maxX), Math.min(Math.max(minX, maxX), offsetX));
		offsetY = Math.max(Math.min(minY, maxY), Math.min(Math.max(minY, maxY), offsetY));
	}

	private void updateCursor() {
		setCursor(hasImage() ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
	}

	// テスト・確認用（今の状態を読む）
	double getScale() {
		return scale;
	}

	double getOffsetX() {
		return offsetX;
	}

	double getOffsetY() {
		return offsetY;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		if (!hasImage()) {
			g2.setColor(new Color(200, 200, 200));
			g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
			String msg = "「画像を選ぶ」で、資料の画像を表示します";
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
			g2.dispose();
			return;
		}

		// 今の倍率に合った大きさの画像を選ぶ（表示の倍率が1以下になる、一番小さい画像）
		int level = 0;
		while (level + 1 < levels.size() && scale * (1 << (level + 1)) <= 1.0) {
			level++;
		}
		double levelScale = scale * (1 << level);

		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		AffineTransform at = new AffineTransform();
		at.translate(offsetX, offsetY);
		at.scale(levelScale, levelScale);
		g2.drawImage(levels.get(level), at, null);

		// 選んだ行に、黄色い印を付ける
		if (highlight != null) {
			double hx = offsetX + highlight.x * scale - 4;
			double hy = offsetY + highlight.y * scale - 3;
			double hw = highlight.width * scale + 8;
			double hh = highlight.height * scale + 6;
			Rectangle2D box = new Rectangle2D.Double(hx, hy, hw, hh);
			g2.setColor(new Color(255, 214, 0, 70));
			g2.fill(box);
			g2.setColor(new Color(255, 120, 0, 230));
			g2.setStroke(new BasicStroke(2f));
			g2.draw(box);
		}

		// 選んだ範囲（青い点線の枠）
		if (selection != null) {
			Rectangle2D sel = new Rectangle2D.Double(offsetX + selection.x * scale, offsetY + selection.y * scale,
					selection.width * scale, selection.height * scale);
			g2.setColor(new Color(30, 110, 255, 45));
			g2.fill(sel);
			g2.setColor(new Color(30, 110, 255, 230));
			g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 8f, 6f }, 0f));
			g2.draw(sel);
		}

		// 右下に、操作の説明と今の倍率を小さく表示する
		String hint1 = "ホイール：拡大縮小　ドラッグ：移動　ダブルクリック：全体表示";
		String hint2 = "Shift＋ドラッグ（右ドラッグ）：範囲を選ぶ　　" + Math.round(scale * 100) + "%";
		g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
		FontMetrics fm = g2.getFontMetrics();
		int w = Math.max(fm.stringWidth(hint1), fm.stringWidth(hint2)) + 16;
		int h = fm.getHeight() * 2 + 8;
		int x = Math.max(4, getWidth() - w - 6);
		int y = getHeight() - h - 6;
		g2.setColor(new Color(0, 0, 0, 150));
		g2.fillRoundRect(x, y, w, h, 10, 10);
		g2.setColor(Color.WHITE);
		g2.drawString(hint1, x + 8, y + 4 + fm.getAscent());
		g2.drawString(hint2, x + 8, y + 4 + fm.getHeight() + fm.getAscent());
		g2.dispose();
	}
}
