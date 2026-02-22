package org.openhab.binding.hyundaibluelink.internal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

@NonNullByDefault
@Component(service = { ThingHandlerFactory.class, HyundaiBlueLinkHandlerFactory.class })
public class HyundaiBlueLinkHandlerFactory extends BaseThingHandlerFactory {

    @Activate
    public HyundaiBlueLinkHandlerFactory() {
    }

    @SuppressWarnings("null")
    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(
            HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE,
            HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE);

    @SuppressWarnings("null")
    private final Set<AccountBridgeHandler> accountHandlers = Collections
            .newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (thingTypeUID.equals(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE)) {
            AccountBridgeHandler handler = new AccountBridgeHandler((Bridge) thing);
            accountHandlers.add(handler);
            return handler;
        } else if (thingTypeUID.equals(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE)) {
            return new HyundaiBlueLinkVehicleHandler(thing);
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        super.removeHandler(thingHandler);
        if (thingHandler instanceof AccountBridgeHandler accountHandler) {
            accountHandlers.remove(accountHandler);
        }
    }

    public Set<AccountBridgeHandler> getAccountHandlers() {
        return accountHandlers;
    }
}
