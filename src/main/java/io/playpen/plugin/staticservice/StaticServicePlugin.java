package io.playpen.plugin.staticservice;

import io.playpen.core.coordinator.network.LocalCoordinator;
import io.playpen.core.coordinator.network.Network;
import io.playpen.core.coordinator.network.ProvisionResult;
import io.playpen.core.coordinator.network.Server;
import io.playpen.core.p3.P3Package;
import io.playpen.core.p3.PackageException;
import io.playpen.core.plugin.AbstractPlugin;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class StaticServicePlugin extends AbstractPlugin {
    @Getter
    private static StaticServicePlugin instance;

    @Getter
    private List<StaticService> services = new CopyOnWriteArrayList<>();

    @Getter
    private long scanRate;

    private AtomicBoolean isScanning = new AtomicBoolean(false);

    @Override
    public boolean onStart() {
        scanRate = getConfig().getLong("scan-rate");

        JSONArray serviceList = getConfig().getJSONArray("services");
        for (int i = 0; i < serviceList.length(); ++i) {
            JSONObject obj = serviceList.getJSONObject(i);
            StaticService service = new StaticService();
            service.setPackageId(obj.getString("package"));
            service.setPackageVersion(obj.getString("version"));
            service.setName(obj.getString("name"));
            service.setCoordinator(obj.getString("coordinator"));

            Map<String, String> strings = new HashMap<>();
            JSONObject jStrings = obj.getJSONObject("strings");
            for (String key : jStrings.keySet()) {
                strings.put(key, jStrings.getString(key));
            }

            service.setStrings(strings);

            services.add(service);
        }

        Network.get().getScheduler().scheduleAtFixedRate(this::scan, scanRate, scanRate, TimeUnit.SECONDS);

        log.info("Maintaining " + services.size() + " static services (scan interval: " + scanRate + ")");

        Network.get().getEventManager().registerListener(new StaticServiceListener());

        return true;
    }

    public void scan() {
        if (!isScanning.compareAndSet(false, true)) {
            Network.get().pluginMessage(this, "log", "Scan already in progress");
            return;
        }

        for (StaticService service : services) {
            LocalCoordinator coord = Network.get().getCoordinator(service.getCoordinator());
            if (coord == null) {
                log.error("Service " + service.getName() + " references invalid coordinator " + service.getCoordinator());
                Network.get().pluginMessage(this, "log", "Service " + service.getName() + " references invalid coordinator " + service.getCoordinator());
                continue;
            }

            if (!coord.isEnabled()) {
                log.warn("Skipping coordinator " + coord.getName() + " as it isn't available");
                Network.get().pluginMessage(this, "log", "Skipping coordinator " + coord.getName() + " as it isn't available");
            }

            // We loop through all servers as we want to check for even inactive servers, since the service might still
            // be in the process of provisioning.
            boolean found = false;
            for (Server server : coord.getServers().values()) {
                if (Objects.equals(server.getName(), service.getName())
                        && Objects.equals(server.getP3().getId(), service.getPackageId())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                log.info("Provisioning static service " + service.getName());
                Network.get().pluginMessage(this, "log", "Provisioning static service " + service.getName());

                P3Package p3 = Network.get().getPackageManager().resolve(service.getPackageId(), service.getPackageVersion());
                if (p3 == null) {
                    log.error("Cannot provision service " + service.getName() + " with invalid package "
                            + service.getPackageId() + " (" + service.getPackageVersion() + ")");
                    Network.get().pluginMessage(this, "log", "Cannot provision service " + service.getName() + " with invalid package "
                            + service.getPackageId() + " (" + service.getPackageVersion() + ")");
                    continue;
                }

                Network.get().provision(p3, service.getName(), service.getStrings(), coord.getUuid());
            }
        }

        isScanning.set(false);
    }
}
