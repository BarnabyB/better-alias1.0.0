package betteralias;

import net.fabricmc.api.ClientModInitializer;

public class BetterAliasClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BetterAliasConfig.load();
		BetterAliasDebugScreen.register();
	}
}
