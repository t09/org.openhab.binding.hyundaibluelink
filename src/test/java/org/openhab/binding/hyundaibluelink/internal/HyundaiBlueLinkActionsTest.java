package org.openhab.binding.hyundaibluelink.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.binding.ThingHandler;

@NonNullByDefault
@SuppressWarnings("null")
class HyundaiBlueLinkActionsTest {

    @Test
    void setThingHandlerStoresAccountBridgeHandler() {
        AccountBridgeHandler handler = mock(AccountBridgeHandler.class);

        HyundaiBlueLinkActions actions = new HyundaiBlueLinkActions();
        actions.setThingHandler(handler);

        assertSame(handler, actions.getThingHandler());
    }

    @Test
    void setThingHandlerRejectsNonAccountHandler() {
        HyundaiBlueLinkActions actions = new HyundaiBlueLinkActions();
        ThingHandler unrelatedHandler = mock(ThingHandler.class);

        assertThrows(IllegalArgumentException.class, () -> actions.setThingHandler(unrelatedHandler));
    }
}
