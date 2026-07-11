package com.creepsinc.clanbank;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class ClanBankOverlay extends OverlayPanel
{
	private final ClanBankPlugin plugin;

	@Inject
	private ClanBankOverlay(ClanBankPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String error = plugin.getLastError();
		ClanBankStatus status = plugin.getStatus();

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(status != null && status.clanName != null ? status.clanName + " Bank" : "Clan Bank")
			.color(Color.YELLOW)
			.build());

		if (error != null && status == null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(error)
				.leftColor(Color.RED)
				.build());
			return super.render(graphics);
		}

		if (status == null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Loading...")
				.build());
			return super.render(graphics);
		}

		if (!status.linked)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("No bank activity found")
				.leftColor(Color.GRAY)
				.build());
			return super.render(graphics);
		}

		// Compact summary only — per-item breakdown, discord tag, etc. live
		// in the sidebar panel instead (click the toolbar icon).
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Owed:")
			.right(status.noLimit ? status.totalOutstandingGp + " gp (no limit)" : status.totalOutstandingGp + " / " + status.loanCapGp + " gp")
			.rightColor(!status.noLimit && status.totalOutstandingGp >= status.loanCapGp ? Color.RED : Color.WHITE)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Pending approval:")
			.right(String.valueOf(status.pendingCount))
			.rightColor(status.pendingCount > 0 ? Color.ORANGE : Color.WHITE)
			.build());

		return super.render(graphics);
	}
}
