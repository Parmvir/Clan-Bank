package com.creepsinc.clanbank;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;

/**
 * Full detail view for the clan bank status — opened from the sidebar icon.
 * The overlay only shows a compact "owed / pending" summary; this panel has
 * everything else: discord link, per-item breakdown with icons, and forms
 * to request or settle a loan directly without going to Discord (both still
 * go through the exact same officer-approval flow as the slash commands).
 */
class ClanBankPanel extends PluginPanel
{
	private static final int ICON_SIZE = 32;
	private static final Color CARD_BACKGROUND = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color OK_GREEN = new Color(0x8B, 0xC3, 0x4A);

	// Rebuilt every poll refresh (~30s) — read-only status display.
	private final JPanel content = new JPanel();
	// Built once — action forms live here so in-progress user input (typed
	// item names, quantities) doesn't get wiped out by a poll refresh.
	private final JPanel actions = new JPanel();

	@Inject
	private ItemManager itemManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Client client;

	@Inject
	private ClanBankConfig config;

	@Inject
	private ClanBankApiClient apiClient;

	// Item name -> resolved icon, so repeated poll refreshes don't re-search
	// the same items over and over.
	private final Map<String, AsyncBufferedImage> iconCache = new ConcurrentHashMap<>();

	// --- Request Item form state ---
	private JTextField requestItemField;
	private JPanel suggestionsPanel;
	private JPanel previewCard;
	private JLabel previewIcon;
	private JLabel previewName;
	private JLabel previewMeta;
	private JLabel previewNotes;
	private JPanel requestFinalProductArea;
	private JComboBox<String> requestFinalProductCombo;
	private JTextField requestFinalProductFreeText;
	private JSpinner requestQuantitySpinner;
	private JLabel requestSummaryLabel;
	private JButton requestSubmitButton;
	private JLabel requestStatusLabel;
	private ItemLookupResult currentLookup;
	private final Timer searchDebounce = new Timer(350, e -> performSearch());

	// --- Return / Settle form state ---
	private JComboBox<LoanChoice> returnLoanCombo;
	private JSpinner returnAmountSpinner;
	private JLabel returnAmountCaption;
	private JButton returnSubmitButton;
	private JLabel returnStatusLabel;
	private JLabel returnEmptyLabel;
	private JPanel returnFormCard;

	@Inject
	private ClanBankPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchDebounce.setRepeats(false);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(new EmptyBorder(10, 10, 0, 10));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setAlignmentX(LEFT_ALIGNMENT);

		actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
		actions.setBorder(new EmptyBorder(10, 10, 10, 10));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setAlignmentX(LEFT_ALIGNMENT);
		buildActionForms();

		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		outer.add(content);
		outer.add(actions);
		add(outer, BorderLayout.NORTH);
	}

	void update(ClanBankStatus status, String lastError)
	{
		SwingUtilities.invokeLater(() -> render(status, lastError));
	}

	private JLabel label(String text, Color color, float size, boolean bold)
	{
		JLabel l = new JLabel(text);
		l.setForeground(color);
		l.setFont((bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont()).deriveFont(size));
		return l;
	}

	private JPanel titleBar(String text)
	{
		JPanel titleBar = new JPanel(new BorderLayout());
		titleBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleBar.setBorder(new EmptyBorder(0, 0, 8, 0));

		JLabel title = label(text, ColorScheme.BRAND_ORANGE, 15f, true);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		titleBar.add(title, BorderLayout.NORTH);

		JSeparator sep = new JSeparator();
		sep.setForeground(ColorScheme.BRAND_ORANGE);
		titleBar.add(sep, BorderLayout.SOUTH);
		return titleBar;
	}

	private JPanel card(JPanel inner)
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(CARD_BACKGROUND);
		wrapper.setBorder(new EmptyBorder(8, 10, 8, 10));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		inner.setBackground(CARD_BACKGROUND);
		wrapper.add(inner, BorderLayout.CENTER);
		return wrapper;
	}

	private void addVerticalGap(int px)
	{
		content.add(Box.createVerticalStrut(px));
	}

	private void addSpacer(JPanel target, int px)
	{
		target.add(Box.createVerticalStrut(px));
	}

	// ------------------------------------------------------------------
	// Read-only status display (rebuilt every poll)
	// ------------------------------------------------------------------

	private void render(ClanBankStatus status, String lastError)
	{
		content.removeAll();
		String clanTitle = status != null && status.clanName != null ? status.clanName + " Clan Bank" : "Clan Bank";
		content.add(titleBar(clanTitle));

		if (lastError != null && status == null)
		{
			JPanel msg = new JPanel();
			msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
			msg.add(label(lastError, ColorScheme.PROGRESS_ERROR_COLOR, 12f, true));
			content.add(card(msg));
			refreshReturnOptions(null);
			finishRender();
			return;
		}

		if (status == null)
		{
			JPanel msg = new JPanel();
			msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
			msg.add(label("Loading...", ColorScheme.LIGHT_GRAY_COLOR, 12f, false));
			content.add(card(msg));
			refreshReturnOptions(null);
			finishRender();
			return;
		}

		if (!status.linked)
		{
			JPanel msg = new JPanel();
			msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
			msg.add(label("No bank activity found", ColorScheme.LIGHT_GRAY_COLOR, 12f, true));
			addVerticalGap(4);
			for (String l : new String[] {
				"Borrow, return, or donate something",
				"via the Discord bank panel, or run",
				"/link-rsn if your Discord name",
				"doesn't match your RSN.",
			})
			{
				msg.add(label(l, ColorScheme.LIGHT_GRAY_COLOR, 11f, false));
			}
			content.add(card(msg));
			refreshReturnOptions(null);
			finishRender();
			return;
		}

		// --- Status card: discord link, owed total, pending count ---
		JPanel statusCard = new JPanel();
		statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));
		statusCard.add(label("Linked as " + status.discordTag, ColorScheme.LIGHT_GRAY_COLOR, 11f, false));
		addSpacer(statusCard, 4);

		boolean overCap = !status.noLimit && status.totalOutstandingGp >= status.loanCapGp;
		JPanel owedRow = new JPanel(new BorderLayout());
		owedRow.setBackground(CARD_BACKGROUND);
		owedRow.add(label("Owed", ColorScheme.LIGHT_GRAY_COLOR, 12f, false), BorderLayout.WEST);
		JLabel owedValue = label(
			status.noLimit
				? String.format("%,d gp (no limit)", status.totalOutstandingGp)
				: String.format("%,d / %,d gp", status.totalOutstandingGp, status.loanCapGp),
			overCap ? ColorScheme.PROGRESS_ERROR_COLOR : Color.WHITE,
			12f,
			true
		);
		owedValue.setHorizontalAlignment(SwingConstants.RIGHT);
		owedRow.add(owedValue, BorderLayout.EAST);
		statusCard.add(owedRow);

		JPanel pendingRow = new JPanel(new BorderLayout());
		pendingRow.setBackground(CARD_BACKGROUND);
		pendingRow.add(label("Pending approval", ColorScheme.LIGHT_GRAY_COLOR, 12f, false), BorderLayout.WEST);
		JLabel pendingValue = label(
			String.valueOf(status.pendingCount),
			status.pendingCount > 0 ? ColorScheme.BRAND_ORANGE : Color.WHITE,
			12f,
			true
		);
		pendingValue.setHorizontalAlignment(SwingConstants.RIGHT);
		pendingRow.add(pendingValue, BorderLayout.EAST);
		statusCard.add(pendingRow);

		content.add(card(statusCard));
		addVerticalGap(10);

		// --- Items section ---
		JLabel itemsHeader = label("ITEMS ON LOAN", ColorScheme.BRAND_ORANGE, 11f, true);
		content.add(itemsHeader);
		addVerticalGap(6);

		if (status.loans == null || status.loans.isEmpty())
		{
			JPanel msg = new JPanel();
			msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
			msg.add(label("Nothing on loan", OK_GREEN, 12f, false));
			content.add(card(msg));
		}
		else
		{
			for (ClanBankStatus.LoanEntry loan : status.loans)
			{
				content.add(itemRow(loan));
				addVerticalGap(6);
			}
		}

		refreshReturnOptions(status);
		finishRender();
	}

	private JPanel itemRow(ClanBankStatus.LoanEntry loan)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBackground(CARD_BACKGROUND);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		row.add(icon, BorderLayout.WEST);
		loadIconInto(loan.itemName, icon);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(CARD_BACKGROUND);

		JPanel nameRow = new JPanel(new BorderLayout());
		nameRow.setBackground(CARD_BACKGROUND);
		nameRow.add(label(loan.itemName, Color.WHITE, 12f, true), BorderLayout.WEST);

		String owed = "gp".equals(loan.repaymentType)
			? String.format("%,d gp", loan.gpOwed)
			: String.format("%,dx", loan.quantityOwed);
		JLabel owedLabel = label(owed, ColorScheme.LIGHT_GRAY_COLOR, 11f, false);
		owedLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		nameRow.add(owedLabel, BorderLayout.EAST);
		text.add(nameRow);

		if (loan.finalProduct != null && !loan.finalProduct.isEmpty()
			&& !loan.finalProduct.equalsIgnoreCase(loan.itemName))
		{
			text.add(label("for: " + loan.finalProduct, ColorScheme.LIGHT_GRAY_COLOR, 10f, false));
		}

		row.add(text, BorderLayout.CENTER);
		return row;
	}

	private void loadIconInto(String itemName, JLabel target)
	{
		AsyncBufferedImage cached = iconCache.get(itemName);
		if (cached != null)
		{
			cached.addTo(target);
			return;
		}

		executor.execute(() ->
		{
			try
			{
				List<ItemPrice> results = itemManager.search(itemName);
				if (results.isEmpty())
				{
					return;
				}
				ItemPrice best = results.stream()
					.filter(r -> r.getName().equalsIgnoreCase(itemName))
					.findFirst()
					.orElse(results.get(0));

				AsyncBufferedImage image = itemManager.getImage(best.getId());
				iconCache.put(itemName, image);
				SwingUtilities.invokeLater(() -> image.addTo(target));
			}
			catch (Exception e)
			{
				// Icon is cosmetic — a failed lookup just leaves the row without one.
			}
		});
	}

	private void finishRender()
	{
		content.revalidate();
		content.repaint();
	}

	// ------------------------------------------------------------------
	// Request Item form (built once, persists across poll refreshes)
	// ------------------------------------------------------------------

	private void buildActionForms()
	{
		actions.add(titleBar("Request Item"));

		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.setBackground(CARD_BACKGROUND);

		JLabel searchCaption = label("Search for an item to borrow", ColorScheme.LIGHT_GRAY_COLOR, 11f, false);
		searchCaption.setAlignmentX(LEFT_ALIGNMENT);
		form.add(searchCaption);
		addSpacer(form, 4);

		requestItemField = new JTextField();
		requestItemField.setToolTipText("Start typing an OSRS item name");
		requestItemField.setAlignmentX(LEFT_ALIGNMENT);
		requestItemField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}
		});
		form.add(requestItemField);
		addSpacer(form, 4);

		suggestionsPanel = new JPanel();
		suggestionsPanel.setLayout(new BoxLayout(suggestionsPanel, BoxLayout.Y_AXIS));
		suggestionsPanel.setBackground(CARD_BACKGROUND);
		suggestionsPanel.setAlignmentX(LEFT_ALIGNMENT);
		suggestionsPanel.setVisible(false);
		form.add(suggestionsPanel);
		addSpacer(form, 6);

		// --- Selected item preview (hidden until something's picked) ---
		previewCard = new JPanel(new BorderLayout(8, 0));
		previewCard.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		previewCard.setBorder(new EmptyBorder(6, 8, 6, 8));
		previewCard.setAlignmentX(LEFT_ALIGNMENT);
		previewCard.setVisible(false);

		previewIcon = new JLabel();
		previewIcon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		previewCard.add(previewIcon, BorderLayout.WEST);

		JPanel previewText = new JPanel();
		previewText.setLayout(new BoxLayout(previewText, BoxLayout.Y_AXIS));
		previewText.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		previewName = label(" ", Color.WHITE, 12f, true);
		previewMeta = label(" ", ColorScheme.LIGHT_GRAY_COLOR, 10f, false);
		previewNotes = label(" ", ColorScheme.BRAND_ORANGE, 10f, false);
		previewText.add(previewName);
		previewText.add(previewMeta);
		previewText.add(previewNotes);
		previewCard.add(previewText, BorderLayout.CENTER);

		form.add(previewCard);
		addSpacer(form, 8);

		requestFinalProductArea = new JPanel();
		requestFinalProductArea.setLayout(new BoxLayout(requestFinalProductArea, BoxLayout.Y_AXIS));
		requestFinalProductArea.setBackground(CARD_BACKGROUND);
		requestFinalProductArea.setAlignmentX(LEFT_ALIGNMENT);
		form.add(requestFinalProductArea);

		requestQuantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1_000_000, 1));
		requestQuantitySpinner.addChangeListener(e -> updateRequestSummary());
		form.add(labeledRow("Quantity", requestQuantitySpinner));
		addSpacer(form, 8);

		requestSummaryLabel = label(" ", ColorScheme.LIGHT_GRAY_COLOR, 10f, false);
		requestSummaryLabel.setAlignmentX(LEFT_ALIGNMENT);
		form.add(requestSummaryLabel);
		addSpacer(form, 8);

		requestSubmitButton = new JButton("Send Request");
		requestSubmitButton.setBackground(ColorScheme.BRAND_ORANGE);
		requestSubmitButton.setForeground(Color.BLACK);
		requestSubmitButton.setFont(FontManager.getRunescapeBoldFont());
		requestSubmitButton.setFocusPainted(false);
		requestSubmitButton.setAlignmentX(LEFT_ALIGNMENT);
		requestSubmitButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		requestSubmitButton.setEnabled(false);
		requestSubmitButton.addActionListener(e -> doSubmitBorrow());
		form.add(requestSubmitButton);
		addSpacer(form, 6);

		requestStatusLabel = label(" ", ColorScheme.LIGHT_GRAY_COLOR, 10f, false);
		requestStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
		form.add(requestStatusLabel);

		actions.add(card(form));
		addActionsGap(14);

		// --- Return / Settle Loan form ---
		actions.add(titleBar("Return / Settle Loan"));

		returnEmptyLabel = label("Nothing on loan to settle right now", ColorScheme.LIGHT_GRAY_COLOR, 11f, false);
		returnFormCard = new JPanel();
		returnFormCard.setLayout(new BoxLayout(returnFormCard, BoxLayout.Y_AXIS));
		returnFormCard.setBackground(CARD_BACKGROUND);

		returnLoanCombo = new JComboBox<>();
		returnLoanCombo.setRenderer(new LoanComboRenderer());
		returnLoanCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		returnLoanCombo.addActionListener(e -> onReturnSelectionChanged());
		returnFormCard.add(labeledRow("Settling", returnLoanCombo));
		addSpacer(returnFormCard, 8);

		returnAmountCaption = label("Quantity to return", ColorScheme.LIGHT_GRAY_COLOR, 11f, false);
		returnAmountCaption.setAlignmentX(LEFT_ALIGNMENT);
		returnFormCard.add(returnAmountCaption);
		addSpacer(returnFormCard, 3);

		JPanel amountRow = new JPanel(new BorderLayout(6, 0));
		amountRow.setBackground(CARD_BACKGROUND);
		amountRow.setAlignmentX(LEFT_ALIGNMENT);
		amountRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		returnAmountSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1_000_000, 1));
		amountRow.add(returnAmountSpinner, BorderLayout.CENTER);
		JButton maxButton = new JButton("MAX");
		maxButton.setFont(FontManager.getRunescapeSmallFont());
		maxButton.setFocusable(false);
		maxButton.addActionListener(e ->
		{
			SpinnerNumberModel model = (SpinnerNumberModel) returnAmountSpinner.getModel();
			returnAmountSpinner.setValue(model.getMaximum());
		});
		amountRow.add(maxButton, BorderLayout.EAST);
		returnFormCard.add(amountRow);
		addSpacer(returnFormCard, 10);

		returnSubmitButton = new JButton("Return Item");
		returnSubmitButton.setBackground(ColorScheme.BRAND_ORANGE);
		returnSubmitButton.setForeground(Color.BLACK);
		returnSubmitButton.setFont(FontManager.getRunescapeBoldFont());
		returnSubmitButton.setFocusPainted(false);
		returnSubmitButton.setAlignmentX(LEFT_ALIGNMENT);
		returnSubmitButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		returnSubmitButton.setEnabled(false);
		returnSubmitButton.addActionListener(e -> doSubmitReturn());
		returnFormCard.add(returnSubmitButton);
		addSpacer(returnFormCard, 6);

		returnStatusLabel = label(" ", ColorScheme.LIGHT_GRAY_COLOR, 10f, false);
		returnStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
		returnFormCard.add(returnStatusLabel);

		JPanel returnSection = new JPanel(new BorderLayout());
		returnSection.setBackground(CARD_BACKGROUND);
		returnSection.add(returnEmptyLabel, BorderLayout.NORTH);
		returnSection.add(returnFormCard, BorderLayout.CENTER);
		actions.add(card(returnSection));
		showReturnEmptyState(true);
	}

	private void showReturnEmptyState(boolean empty)
	{
		returnEmptyLabel.setVisible(empty);
		returnFormCard.setVisible(!empty);
	}

	/** Shows the item's icon (already cached from the loan list above) beside its name and owed amount. */
	private class LoanComboRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(
			JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			JLabel rendered = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			rendered.setForeground(Color.WHITE);
			rendered.setBackground(isSelected ? ColorScheme.DARK_GRAY_HOVER_COLOR : CARD_BACKGROUND);
			rendered.setFont(FontManager.getRunescapeSmallFont());
			if (value instanceof LoanChoice)
			{
				LoanChoice choice = (LoanChoice) value;
				rendered.setText(choice.toString());
				AsyncBufferedImage icon = iconCache.get(choice.loan.itemName);
				if (icon != null)
				{
					rendered.setIcon(new ImageIcon(icon.getScaledInstance(16, 16, Image.SCALE_SMOOTH)));
				}
			}
			return rendered;
		}
	}

	private void addActionsGap(int px)
	{
		actions.add(Box.createVerticalStrut(px));
	}

	private JPanel labeledRow(String labelText, JComponent field)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(CARD_BACKGROUND);
		JLabel l = label(labelText, ColorScheme.LIGHT_GRAY_COLOR, 11f, false);
		l.setAlignmentX(LEFT_ALIGNMENT);
		row.add(l);
		field.setAlignmentX(LEFT_ALIGNMENT);
		row.add(field);
		return row;
	}

	// Same "consumed directly" list catalog.js treats as a gp-buyback pick
	// even for raw materials that do have real final products (Cash, etc).
	private static final List<String> NON_ITEM_FINAL_PRODUCTS = Arrays.asList("cash", "firemaking");

	private void performSearch()
	{
		String query = requestItemField.getText() == null ? "" : requestItemField.getText().trim();
		if (query.isEmpty())
		{
			suggestionsPanel.removeAll();
			suggestionsPanel.setVisible(false);
			actions.revalidate();
			actions.repaint();
			return;
		}

		executor.execute(() ->
		{
			try
			{
				SearchResponse resp = apiClient.search(config.apiUrl(), config.apiKey(), query);
				SwingUtilities.invokeLater(() -> showSuggestions(resp.results));
			}
			catch (IOException e)
			{
				// Suggestions are a convenience — a failed search just leaves the list empty.
			}
		});
	}

	private void showSuggestions(List<SearchResult> results)
	{
		suggestionsPanel.removeAll();
		if (results != null)
		{
			for (SearchResult r : results)
			{
				suggestionsPanel.add(suggestionRow(r));
			}
		}
		suggestionsPanel.setVisible(results != null && !results.isEmpty());
		actions.revalidate();
		actions.repaint();
	}

	private JPanel suggestionRow(SearchResult r)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_BACKGROUND);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(20, 20));
		row.add(icon, BorderLayout.WEST);
		loadIconInto(r.name, icon);

		String stockDot = r.stock <= 0 ? "🔴" : (r.lowStock ? "🟡" : "🟢");
		JLabel nameLabel = label(r.name, Color.WHITE, 11f, false);
		row.add(nameLabel, BorderLayout.CENTER);

		JLabel priceLabel = label(stockDot + " " + String.format("%,d gp", r.price), ColorScheme.LIGHT_GRAY_COLOR, 10f, false);
		priceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(priceLabel, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectItem(r.name);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(CARD_BACKGROUND);
			}
		});
		return row;
	}

	private void selectItem(String name)
	{
		searchDebounce.stop();
		requestItemField.setText(name);
		suggestionsPanel.removeAll();
		suggestionsPanel.setVisible(false);
		doLookup(name);
	}

	private void doLookup(String name)
	{
		requestSubmitButton.setEnabled(false);
		requestStatusLabel.setText("Looking up...");
		requestStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		currentLookup = null;

		executor.execute(() ->
		{
			try
			{
				ItemLookupResult result = apiClient.lookupItem(config.apiUrl(), config.apiKey(), name);
				SwingUtilities.invokeLater(() -> applyLookupResult(result));
			}
			catch (ClanBankApiException e)
			{
				SwingUtilities.invokeLater(() ->
				{
					String suggestion = e.getSuggestions().isEmpty() ? "" : (" Did you mean: " + String.join(", ", e.getSuggestions()) + "?");
					requestStatusLabel.setText(e.getMessage() + suggestion);
					requestStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
					previewCard.setVisible(false);
					actions.revalidate();
					actions.repaint();
				});
			}
			catch (IOException e)
			{
				SwingUtilities.invokeLater(() ->
				{
					requestStatusLabel.setText("Bot unreachable");
					requestStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
				});
			}
		});
	}

	private void applyLookupResult(ItemLookupResult result)
	{
		currentLookup = result;
		requestStatusLabel.setText(" ");

		AsyncBufferedImage cached = iconCache.get(result.name);
		if (cached != null)
		{
			cached.addTo(previewIcon);
		}
		else
		{
			loadIconInto(result.name, previewIcon);
		}
		previewName.setText(result.name);
		String stockDot = result.stock <= 0 ? "🔴" : (result.lowStock ? "🟡" : "🟢");
		previewMeta.setText(String.format("%s %,d in stock · %,d gp each", stockDot, result.stock, result.price));
		previewNotes.setText(result.notes != null && !result.notes.isEmpty() ? "⚠ " + result.notes : " ");
		previewCard.setVisible(true);

		requestFinalProductArea.removeAll();
		if (result.finalProducts != null && !result.finalProducts.isEmpty())
		{
			requestFinalProductCombo = new JComboBox<>(result.finalProducts.toArray(new String[0]));
			requestFinalProductCombo.setAlignmentX(LEFT_ALIGNMENT);
			requestFinalProductCombo.addActionListener(e -> updateRequestSummary());
			requestFinalProductArea.add(labeledRow("Making", requestFinalProductCombo));
			requestFinalProductFreeText = null;
		}
		else
		{
			requestFinalProductFreeText = new JTextField();
			requestFinalProductFreeText.setToolTipText("Optional — what you're using this for");
			requestFinalProductFreeText.getDocument().addDocumentListener(new DocumentListener()
			{
				@Override
				public void insertUpdate(DocumentEvent e)
				{
					updateRequestSummary();
				}

				@Override
				public void removeUpdate(DocumentEvent e)
				{
					updateRequestSummary();
				}

				@Override
				public void changedUpdate(DocumentEvent e)
				{
					updateRequestSummary();
				}
			});
			requestFinalProductArea.add(labeledRow("What for (optional)", requestFinalProductFreeText));
			requestFinalProductCombo = null;
		}

		requestSubmitButton.setEnabled(true);
		updateRequestSummary();
		actions.revalidate();
		actions.repaint();
	}

	private String selectedFinalProduct()
	{
		if (requestFinalProductCombo != null)
		{
			return (String) requestFinalProductCombo.getSelectedItem();
		}
		return requestFinalProductFreeText != null ? requestFinalProductFreeText.getText().trim() : "";
	}

	private boolean isGpRepayment(ItemLookupResult item, String finalProduct)
	{
		if (item.finalProducts == null || item.finalProducts.isEmpty())
		{
			return true;
		}
		return finalProduct != null && NON_ITEM_FINAL_PRODUCTS.contains(finalProduct.trim().toLowerCase());
	}

	private boolean isSelfReturn(ItemLookupResult item, String finalProduct)
	{
		return finalProduct != null && !finalProduct.isEmpty()
			&& item.finalProducts != null && item.finalProducts.size() == 1
			&& finalProduct.trim().equalsIgnoreCase(item.name);
	}

	private void updateRequestSummary()
	{
		if (currentLookup == null)
		{
			requestSummaryLabel.setText(" ");
			return;
		}

		int quantity = (Integer) requestQuantitySpinner.getValue();
		String finalProduct = selectedFinalProduct();
		long value = currentLookup.price * quantity;

		String repayLine;
		if (isGpRepayment(currentLookup, finalProduct))
		{
			long gpOwed = Math.round(value * 0.5);
			repayLine = String.format("Consumed — settles via %,d gp buyback", gpOwed);
		}
		else if (isSelfReturn(currentLookup, finalProduct))
		{
			repayLine = "Physical loan — return this exact item";
		}
		else
		{
			repayLine = "Hand back " + finalProduct + " once made";
		}

		requestSummaryLabel.setText(String.format(
			"<html>%,dx %s &mdash; %,d gp<br>%s</html>",
			quantity, currentLookup.name, value, repayLine
		));
	}

	private void doSubmitBorrow()
	{
		if (currentLookup == null)
		{
			return;
		}
		String rsn = localPlayerName();
		if (rsn == null)
		{
			requestStatusLabel.setText("Not logged in");
			requestStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}

		int quantity = (Integer) requestQuantitySpinner.getValue();
		String finalProduct = selectedFinalProduct();

		requestSubmitButton.setEnabled(false);
		requestStatusLabel.setText("Submitting...");
		requestStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		String itemName = currentLookup.name;
		executor.execute(() ->
		{
			try
			{
				ActionResponse resp = apiClient.submitBorrow(config.apiUrl(), config.apiKey(), rsn, itemName, quantity, finalProduct);
				SwingUtilities.invokeLater(() ->
				{
					requestStatusLabel.setText(String.format("Sent for approval: %,dx %s", resp.quantity, resp.itemName));
					requestStatusLabel.setForeground(OK_GREEN);
					requestSubmitButton.setEnabled(true);
				});
			}
			catch (IOException e)
			{
				String message = e instanceof ClanBankApiException ? e.getMessage() : "Bot unreachable";
				SwingUtilities.invokeLater(() ->
				{
					requestStatusLabel.setText(message);
					requestStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
					requestSubmitButton.setEnabled(true);
				});
			}
		});
	}

	// ------------------------------------------------------------------
	// Return / Settle Loan form
	// ------------------------------------------------------------------

	private static class LoanChoice
	{
		final ClanBankStatus.LoanEntry loan;

		LoanChoice(ClanBankStatus.LoanEntry loan)
		{
			this.loan = loan;
		}

		@Override
		public String toString()
		{
			String owed = "gp".equals(loan.repaymentType)
				? String.format("%s (%,d gp)", loan.itemName, loan.gpOwed)
				: String.format("%s (%,dx)", loan.itemName, loan.quantityOwed);
			return owed;
		}
	}

	private void refreshReturnOptions(ClanBankStatus status)
	{
		LoanChoice previouslySelected = (LoanChoice) returnLoanCombo.getSelectedItem();
		List<LoanChoice> choices = new ArrayList<>();
		if (status != null && status.loans != null)
		{
			for (ClanBankStatus.LoanEntry loan : status.loans)
			{
				choices.add(new LoanChoice(loan));
			}
		}

		DefaultComboBoxModel<LoanChoice> model = new DefaultComboBoxModel<>(choices.toArray(new LoanChoice[0]));
		returnLoanCombo.setModel(model);

		if (previouslySelected != null)
		{
			for (int i = 0; i < choices.size(); i++)
			{
				if (choices.get(i).loan.itemName.equals(previouslySelected.loan.itemName))
				{
					returnLoanCombo.setSelectedIndex(i);
					break;
				}
			}
		}

		showReturnEmptyState(choices.isEmpty());
		onReturnSelectionChanged();
	}

	private void onReturnSelectionChanged()
	{
		LoanChoice choice = (LoanChoice) returnLoanCombo.getSelectedItem();
		if (choice == null)
		{
			returnSubmitButton.setEnabled(false);
			return;
		}

		boolean isGp = "gp".equals(choice.loan.repaymentType);
		long owed = isGp ? choice.loan.gpOwed : choice.loan.quantityOwed;
		returnAmountCaption.setText((isGp ? "Gp amount to pay" : "Quantity to return") + String.format(" (max %,d)", owed));
		returnSubmitButton.setText(isGp ? "Pay Loan" : "Return Item");

		// Capped to Integer.MAX_VALUE — SpinnerNumberModel(int,...) must stay
		// entirely int-typed, otherwise Java silently widens to the
		// (double,double,double,double) overload and getValue() below
		// returns a Double instead of an Integer.
		int max = (int) Math.max(1, Math.min(owed, Integer.MAX_VALUE));
		returnAmountSpinner.setModel(new SpinnerNumberModel(1, 1, max, 1));
		returnSubmitButton.setEnabled(true);
	}

	private void doSubmitReturn()
	{
		LoanChoice choice = (LoanChoice) returnLoanCombo.getSelectedItem();
		if (choice == null)
		{
			return;
		}
		String rsn = localPlayerName();
		if (rsn == null)
		{
			returnStatusLabel.setText("Not logged in");
			returnStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}

		int amount = (Integer) returnAmountSpinner.getValue();
		boolean isGp = "gp".equals(choice.loan.repaymentType);
		String itemName = choice.loan.itemName;

		returnSubmitButton.setEnabled(false);
		returnStatusLabel.setText("Submitting...");
		returnStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		executor.execute(() ->
		{
			try
			{
				ActionResponse resp = isGp
					? apiClient.submitPayLoan(config.apiUrl(), config.apiKey(), rsn, itemName, amount)
					: apiClient.submitReturn(config.apiUrl(), config.apiKey(), rsn, itemName, amount);
				SwingUtilities.invokeLater(() ->
				{
					String msg = isGp
						? String.format("Payment sent for confirmation: %,d gp", resp.amountGp)
						: String.format("Return sent for confirmation: %,dx %s", resp.quantity, resp.itemName);
					returnStatusLabel.setText(msg);
					returnStatusLabel.setForeground(OK_GREEN);
					returnSubmitButton.setEnabled(true);
				});
			}
			catch (IOException e)
			{
				String message = e instanceof ClanBankApiException ? e.getMessage() : "Bot unreachable";
				SwingUtilities.invokeLater(() ->
				{
					returnStatusLabel.setText(message);
					returnStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
					returnSubmitButton.setEnabled(true);
				});
			}
		});
	}

	private String localPlayerName()
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}
		return client.getLocalPlayer().getName();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
	}
}
