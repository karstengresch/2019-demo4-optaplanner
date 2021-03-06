package com.redhat.demo.optaplanner.upstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.demo.optaplanner.Mechanic;
import com.redhat.demo.optaplanner.upstream.utils.GameConfigListener;
import com.redhat.demo.optaplanner.upstream.utils.OptaPlannerConfig;
import com.redhat.demo.optaplanner.websocket.domain.JsonMechanic;
import com.redhat.demo.optaplanner.websocket.response.AddMechanicResponse;
import com.redhat.demo.optaplanner.websocket.response.DispatchMechanicResponse;
import com.redhat.demo.optaplanner.websocket.response.FutureVisitsResponse;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.RemoteCounterManagerFactory;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.counter.api.CounterManager;
import org.infinispan.counter.api.StrongCounter;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

class InfinispanConnector implements UpstreamConnector {

    private static final long FULL_HEALTH = 1_000_000_000_000_000_000L;
    private static final String DISPATCH_MECHANIC_EVENTS_CACHE_NAME = "DispatchEvents";
    private static final String GAME_CACHE_NAME = "game";
    public static final String OPTA_PLANNER_CONFIG_KEY_NAME = "OptaPlannerConfig";

    private StrongCounter[] counters;
    private final int maximumMechanicsSize;
    private RemoteCache<String, String> dispatchMechanicEventsCache;
    private RemoteCache<String, String> gameCache;
    private RemoteCacheManager remoteCacheManager;
    private ObjectMapper objectMapper;
    private ForkJoinPool customForkJoinPool;

    protected InfinispanConnector(int machineHealthCountersCount,
                                  int maximumMechanicsSize,
                                  GameConfigListener gameConfigListener) {
        counters = new StrongCounter[machineHealthCountersCount];
        this.maximumMechanicsSize = maximumMechanicsSize;
        Configuration configuration = HotRodClientConfiguration.get().build();
        remoteCacheManager = new RemoteCacheManager(configuration);
        CounterManager counterManager = RemoteCounterManagerFactory.asCounterManager(remoteCacheManager);
        for (int i = 0; i < counters.length; i++) {
            StrongCounter currentCounter = counterManager.getStrongCounter(String.format("machine-%d", i));
            counters[i] = currentCounter;
        }
        dispatchMechanicEventsCache = remoteCacheManager.getCache(DISPATCH_MECHANIC_EVENTS_CACHE_NAME);
        gameCache = remoteCacheManager.getCache(GAME_CACHE_NAME);
        gameCache.addClientListener(gameConfigListener);
        objectMapper = new ObjectMapper();

        OptaPlannerConfig defaultConfig = new OptaPlannerConfig(false, false);
        gameCache.put(OPTA_PLANNER_CONFIG_KEY_NAME, convertToJsonString(defaultConfig));
        customForkJoinPool = new ForkJoinPool(machineHealthCountersCount);
    }

    public void disconnect() {
        customForkJoinPool.shutdownNow();
        remoteCacheManager.stop();
    }

    @Override
    public double[] fetchMachineHealths() {
        if (customForkJoinPool.isTerminated() || customForkJoinPool.isShutdown()) {
            throw new InfinispanException("Thread pool has been terminated probably due to a lost connection to Infinispan.");
        }
        try {
            return customForkJoinPool.submit(
                    () -> IntStream.range(0, counters.length).parallel()
                            .mapToLong(i -> {
                                try {
                                    return counters[i].getValue().get();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("Custom thread was interrupted while getting counter value.", e);
                                } catch (ExecutionException e) {
                                    throw new InfinispanException("Couldn't find StringCounter (" + i + ").", e.getCause());
                                }
                            })
                            .mapToDouble(machineHealthLong -> ((double) machineHealthLong) / ((double) FULL_HEALTH))
                            .toArray()
            ).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Connector thread was interrupted while getting counters values.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InfinispanException) {
                throw (InfinispanException) e.getCause();
            } else {
                throw new IllegalStateException("Unknown exception occurred when fetching machine healths", e.getCause());
            }
        } catch (RejectedExecutionException ree) {
            // do nothing and wait for reconnect, which will re-create the thread pool
            throw new InfinispanException("Rejected execution probably caused by losing connection to Infinispan.", ree);
        }
    }

    @Override
    public void resetMachineHealth(int machineIndex) {
        try {
            counters[machineIndex].reset();
        } catch (RejectedExecutionException ex) {
            throw new InfinispanException("Unable to reset health of the machine-" + machineIndex + ".", ex);
        }
    }

    @Override
    public void dispatchMechanic(Mechanic mechanic, long currentTimeMillis) {
        JsonMechanic jsonMechanic = new JsonMechanic(mechanic, currentTimeMillis);
        DispatchMechanicResponse dispatchMechanicResponse = new DispatchMechanicResponse(jsonMechanic);
        dispatchMechanicEventsCache.put(String.valueOf(jsonMechanic.getMechanicIndex()), convertToJsonString(dispatchMechanicResponse));
    }

    @Override
    public void mechanicAdded(Mechanic mechanic, long currentTimeMillis) {
        JsonMechanic jsonMechanic = new JsonMechanic(mechanic, currentTimeMillis);
        AddMechanicResponse addMechanicResponse = new AddMechanicResponse(jsonMechanic);
        dispatchMechanicEventsCache.put(String.valueOf(mechanic.getMechanicIndex()), convertToJsonString(addMechanicResponse));
    }

    @Override
    public void mechanicRemoved(Mechanic mechanic) {
        removeMechanicFromInfinispan(mechanic.getMechanicIndex());
    }

    @Override
    public void clearMechanicsAndFutureVisits() {
        for (int mechanicIndex = 0; mechanicIndex < maximumMechanicsSize; mechanicIndex++) {
            removeMechanicFromInfinispan(mechanicIndex);
        }
    }

    private void removeMechanicFromInfinispan(int mechanicIndex) {
        dispatchMechanicEventsCache.remove(String.valueOf(mechanicIndex));
        dispatchMechanicEventsCache.remove(String.format("%d-futureIndexes", mechanicIndex));
    }

    @Override
    public void damageMachine(int machineIndex, double damage) {
        long damageLong = (long) (damage * FULL_HEALTH);
        try {
            counters[machineIndex].addAndGet(-damageLong);
        } catch (RejectedExecutionException ex) {
            throw new InfinispanException("Unable to damage the machine-" + machineIndex + ".", ex);
        }
    }

    @Override
    public void sendFutureVisits(int mechanicIndex, int [] futureMachineIndexes) {
        FutureVisitsResponse futureVisitsResponse = new FutureVisitsResponse(mechanicIndex, futureMachineIndexes);
        dispatchMechanicEventsCache.put(String.format("%d-futureIndexes", mechanicIndex), convertToJsonString(futureVisitsResponse));
    }

    @Override
    public synchronized void setDispatchStatus(boolean isDispatchActive) {
        OptaPlannerConfig config = getOptaPlannerConfig();
        config.setDispatchActive(isDispatchActive);
        gameCache.put(OPTA_PLANNER_CONFIG_KEY_NAME, convertToJsonString(config));
    }

    @Override
    public synchronized void setSimulationStatus(boolean isSimulationActive) {
        OptaPlannerConfig config = getOptaPlannerConfig();
        config.setSimulationActive(isSimulationActive);
        gameCache.put(OPTA_PLANNER_CONFIG_KEY_NAME, convertToJsonString(config));
    }

    private OptaPlannerConfig getOptaPlannerConfig() {
        String jsonString = gameCache.get(OPTA_PLANNER_CONFIG_KEY_NAME);
        if (jsonString == null) {
            throw new InfinispanException(OPTA_PLANNER_CONFIG_KEY_NAME + " was null. Try to reconnect.");
        }
        return convertFromJsonString(jsonString, OptaPlannerConfig.class);
    }

    private String convertToJsonString(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not format " + object.getClass().getName() + " as json.", e);
        }
    }

    private <T> T convertFromJsonString(String json, Class<T> tClass) {
        try {
            return objectMapper.readValue(json, tClass);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not convert " + json + "to " + tClass.getName());
        }
    }
}
