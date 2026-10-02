package jp.example;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * 横に並べたボタンが、幅が足りないときに、次の行へ折り返して全部見えるようにする配置（FlowLayoutの改良版）。
 * ふつうのFlowLayoutは、幅が足りないと、はみ出したボタンが隠れてしまう。
 */
public class WrapLayout extends FlowLayout {
	private static final long serialVersionUID = 1L;

	public WrapLayout(int align, int hgap, int vgap) {
		super(align, hgap, vgap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target) {
		return layoutSize(target, true);
	}

	/**
	 * 最小の大きさ：折り返せるので、幅は「一番幅のある部品1つ分」でよい。
	 * （全部を並べた幅にすると、この部品を含む画面の右側が「これ以上縮められない」ことになり、
	 * 　画像と表の境目を動かせなくなる。）
	 */
	@Override
	public Dimension minimumLayoutSize(Container target) {
		synchronized (target.getTreeLock()) {
			Insets insets = target.getInsets();
			int widest = 0;
			int tallest = 0;
			for (int i = 0; i < target.getComponentCount(); i++) {
				Component m = target.getComponent(i);
				if (m.isVisible()) {
					Dimension d = m.getMinimumSize();
					widest = Math.max(widest, d.width);
					tallest = Math.max(tallest, d.height);
				}
			}
			return new Dimension(widest + insets.left + insets.right + getHgap() * 2,
					tallest + insets.top + insets.bottom + getVgap() * 2);
		}
	}

	/** 今の幅で折り返したときに必要な大きさを計算する。 */
	private Dimension layoutSize(Container target, boolean preferred) {
		synchronized (target.getTreeLock()) {
			// 幅が決まっていない間は、親の幅を使う
			Container c = target;
			while (c.getWidth() == 0 && c.getParent() != null) {
				c = c.getParent();
			}
			int targetWidth = c.getWidth() == 0 ? Integer.MAX_VALUE : c.getWidth();

			Insets insets = target.getInsets();
			int insetsAndGap = insets.left + insets.right + getHgap() * 2;
			int maxWidth = targetWidth - insetsAndGap;

			Dimension dim = new Dimension(0, 0);
			int rowWidth = 0;
			int rowHeight = 0;
			for (int i = 0; i < target.getComponentCount(); i++) {
				Component m = target.getComponent(i);
				if (!m.isVisible()) {
					continue;
				}
				Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
				if (rowWidth + d.width > maxWidth && rowWidth > 0) {
					addRow(dim, rowWidth, rowHeight);
					rowWidth = 0;
					rowHeight = 0;
				}
				if (rowWidth != 0) {
					rowWidth += getHgap();
				}
				rowWidth += d.width;
				rowHeight = Math.max(rowHeight, d.height);
			}
			addRow(dim, rowWidth, rowHeight);
			dim.width += insetsAndGap;
			dim.height += insets.top + insets.bottom + getVgap() * 2;
			return dim;
		}
	}

	private void addRow(Dimension dim, int rowWidth, int rowHeight) {
		dim.width = Math.max(dim.width, rowWidth);
		if (dim.height > 0) {
			dim.height += getVgap();
		}
		dim.height += rowHeight;
	}
}
