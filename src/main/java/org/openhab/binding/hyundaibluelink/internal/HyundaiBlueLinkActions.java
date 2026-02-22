package org.openhab.binding.hyundaibluelink.internal;

import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

@Component(service = ThingActions.class, configurationPid = "binding.hyundaibluelink", scope = ServiceScope.PROTOTYPE)
@ThingActionsScope(name = "hyundaibluelink")
public class HyundaiBlueLinkActions implements ThingActions {

    private AccountBridgeHandler handler;

    @Override
    public void setThingHandler(@org.eclipse.jdt.annotation.NonNull ThingHandler thingHandler) {
        if (thingHandler instanceof AccountBridgeHandler) {
            handler = (AccountBridgeHandler) thingHandler;
        } else {
            throw new IllegalArgumentException("Hyundai BlueLink actions only support AccountBridgeHandler");
        }
    }

    @Override
    public ThingHandler getThingHandler() {
        // May be null while the bridge is not yet wired – that's fine
        return handler;
    }

}
