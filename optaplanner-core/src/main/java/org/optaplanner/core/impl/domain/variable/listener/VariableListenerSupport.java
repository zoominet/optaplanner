/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.core.impl.domain.variable.listener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.optaplanner.core.impl.domain.entity.descriptor.EntityDescriptor;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import org.optaplanner.core.impl.domain.variable.descriptor.ShadowVariableDescriptor;
import org.optaplanner.core.impl.domain.variable.descriptor.VariableDescriptor;
import org.optaplanner.core.impl.domain.variable.supply.Demand;
import org.optaplanner.core.impl.domain.variable.supply.Supply;
import org.optaplanner.core.impl.domain.variable.supply.SupplyManager;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;

public class VariableListenerSupport implements SupplyManager {

    protected final InnerScoreDirector scoreDirector;

    protected final Map<VariableDescriptor, List<VariableListenerNotifiable>> sourceVariableToNotifiableMap;
    protected final Map<EntityDescriptor, List<VariableListenerNotifiable>> sourceEntityToNotifiableMap;
    protected final Map<Demand, Supply> supplyMap;
    protected int nextGlobalOrder = 0;

    protected SortedMap<VariableListenerNotifiable, Set<VariableListenerNotification>> notificationQueueMap;

    public VariableListenerSupport(InnerScoreDirector scoreDirector) {
        this.scoreDirector = scoreDirector;
        sourceVariableToNotifiableMap = new LinkedHashMap<VariableDescriptor, List<VariableListenerNotifiable>>();
        sourceEntityToNotifiableMap = new LinkedHashMap<EntityDescriptor, List<VariableListenerNotifiable>>();
        supplyMap = new LinkedHashMap<Demand, Supply>();
    }

    public void linkVariableListeners() {
        notificationQueueMap = new TreeMap<VariableListenerNotifiable, Set<VariableListenerNotification>>();
        for (EntityDescriptor entityDescriptor : scoreDirector.getSolutionDescriptor().getEntityDescriptors()) {
            for (VariableDescriptor variableDescriptor : entityDescriptor.getDeclaredVariableDescriptors()) {
                List<VariableListenerNotifiable> variableNotifiableList = new ArrayList<VariableListenerNotifiable>();
                sourceVariableToNotifiableMap.put(variableDescriptor, variableNotifiableList);
            }
            List<VariableListenerNotifiable> entityNotifiableList = new ArrayList<VariableListenerNotifiable>();
            sourceEntityToNotifiableMap.put(entityDescriptor, entityNotifiableList);
        }
        for (EntityDescriptor entityDescriptor : scoreDirector.getSolutionDescriptor().getEntityDescriptors()) {
            for (ShadowVariableDescriptor shadowVariableDescriptor : entityDescriptor.getDeclaredShadowVariableDescriptors()) {
                if (shadowVariableDescriptor.hasVariableListener(scoreDirector)) {
                    VariableListener variableListener = shadowVariableDescriptor.buildVariableListener(scoreDirector);
                    supplyMap.put(shadowVariableDescriptor.getProvidedDemand(), variableListener);
                    int globalOrder = shadowVariableDescriptor.getGlobalShadowOrder();
                    if (nextGlobalOrder <= globalOrder) {
                        nextGlobalOrder = globalOrder + 1;
                    }
                    VariableListenerNotifiable notifiable = new VariableListenerNotifiable(variableListener, globalOrder);
                    for (VariableDescriptor source : shadowVariableDescriptor.getSourceVariableDescriptorList()) {
                        List<VariableListenerNotifiable> variableNotifiableList = sourceVariableToNotifiableMap.get(source);
                        variableNotifiableList.add(notifiable);
                        List<VariableListenerNotifiable> entityNotifiableList = sourceEntityToNotifiableMap.get(source.getEntityDescriptor());
                        if (!entityNotifiableList.contains(notifiable)) {
                            entityNotifiableList.add(notifiable);
                        }
                    }
                    Set<VariableListenerNotification> notificationQueue = new LinkedHashSet<VariableListenerNotification>();
                    notificationQueueMap.put(notifiable, notificationQueue);
                }
            }
        }
    }

    public <S extends Supply> S demand(Demand<S> demand) {
        S supply = (S) supplyMap.get(demand);
        if (supply == null) {
            supply = demand.createExternalizedSupply(scoreDirector);
            if (supply instanceof StatefulVariableListener) {
                StatefulVariableListener variableListener = (StatefulVariableListener) supply;
                variableListener.resetWorkingSolution(scoreDirector);
                VariableDescriptor source = variableListener.getSourceVariableDescriptor();
                VariableListenerNotifiable notifiable = new VariableListenerNotifiable(variableListener, nextGlobalOrder);
                nextGlobalOrder++;
                List<VariableListenerNotifiable> variableNotifiableList = sourceVariableToNotifiableMap.get(source);
                variableNotifiableList.add(notifiable);
                List<VariableListenerNotifiable> entityNotifiableList = sourceEntityToNotifiableMap.get(source.getEntityDescriptor());
                if (!entityNotifiableList.contains(notifiable)) {
                    entityNotifiableList.add(notifiable);
                }
                Set<VariableListenerNotification> notificationQueue = new LinkedHashSet<VariableListenerNotification>();
                notificationQueueMap.put(notifiable, notificationQueue);
            }
            supplyMap.put(demand, supply);
        }
        return supply;
    }

    // ************************************************************************
    // Lifecycle methods
    // ************************************************************************

    public void resetWorkingSolution() {
        for (VariableListenerNotifiable notifiable : notificationQueueMap.keySet()) {
            VariableListener variableListener = notifiable.getVariableListener();
            if (variableListener instanceof StatefulVariableListener) {
                ((StatefulVariableListener) variableListener).resetWorkingSolution(scoreDirector);
            }
        }
    }

    public void clearWorkingSolution() {
        for (VariableListenerNotifiable notifiable : notificationQueueMap.keySet()) {
            VariableListener variableListener = notifiable.getVariableListener();
            if (variableListener instanceof StatefulVariableListener) {
                ((StatefulVariableListener) variableListener).clearWorkingSolution(scoreDirector);
            }
        }
    }

    public void beforeEntityAdded(EntityDescriptor entityDescriptor, Object entity) {
        List<VariableListenerNotifiable> notifiableList = sourceEntityToNotifiableMap.get(entityDescriptor);
        for (VariableListenerNotifiable notifiable : notifiableList) {
            Set<VariableListenerNotification> notificationQueue = notificationQueueMap.get(notifiable);
            boolean added = notificationQueue.add(
                    new VariableListenerNotification(entity, VariableListenerNotificationType.ENTITY_ADDED));
            if (added) {
                notifiable.getVariableListener().beforeEntityAdded(scoreDirector, entity);
            }
        }
    }

    public void afterEntityAdded(EntityDescriptor entityDescriptor, Object entity) {
        // beforeEntityAdded() has already added  it to the notificationQueue
    }

    public void beforeVariableChanged(VariableDescriptor variableDescriptor, Object entity) {
        List<VariableListenerNotifiable> notifiableList = sourceVariableToNotifiableMap.get(variableDescriptor);
        for (VariableListenerNotifiable notifiable : notifiableList) {
            Set<VariableListenerNotification> notificationQueue = notificationQueueMap.get(notifiable);
            boolean added = notificationQueue.add(
                    new VariableListenerNotification(entity, VariableListenerNotificationType.VARIABLE_CHANGED));
            if (added) {
                notifiable.getVariableListener().beforeVariableChanged(scoreDirector, entity);
            }
        }
    }

    public void afterVariableChanged(VariableDescriptor variableDescriptor, Object entity) {
        // beforeVariableChanged() has already added  it to the notificationQueue
    }

    public void beforeEntityRemoved(EntityDescriptor entityDescriptor, Object entity) {
        List<VariableListenerNotifiable> notifiableList = sourceEntityToNotifiableMap.get(entityDescriptor);
        for (VariableListenerNotifiable notifiable : notifiableList) {
            Set<VariableListenerNotification> notificationQueue = notificationQueueMap.get(notifiable);
            boolean added = notificationQueue.add(
                    new VariableListenerNotification(entity, VariableListenerNotificationType.ENTITY_REMOVED));
            if (added) {
                notifiable.getVariableListener().beforeEntityRemoved(scoreDirector, entity);
            }
        }
    }

    public void afterEntityRemoved(EntityDescriptor entityDescriptor, Object entity) {
        // beforeEntityRemoved() has already added  it to the notificationQueue
    }

    public void triggerVariableListenersInNotificationQueues() {
        for (Map.Entry<VariableListenerNotifiable, Set<VariableListenerNotification>> entry : notificationQueueMap.entrySet()) {
            VariableListenerNotifiable notifiable = entry.getKey();
            Set<VariableListenerNotification> notificationQueue = entry.getValue();
            VariableListener variableListener = notifiable.getVariableListener();
            for (Iterator<VariableListenerNotification> it = notificationQueue.iterator(); it.hasNext(); ) {
                VariableListenerNotification notification = it.next();
                Object entity = notification.getEntity();
                switch (notification.getType()) {
                    case ENTITY_ADDED:
                        variableListener.afterEntityAdded(scoreDirector, entity);
                        break;
                    case VARIABLE_CHANGED:
                        variableListener.afterVariableChanged(scoreDirector, entity);
                        break;
                    case ENTITY_REMOVED:
                        variableListener.afterEntityRemoved(scoreDirector, entity);
                        break;
                    default:
                        throw new IllegalStateException("The variableListenerNotificationType ("
                                + notification.getType() + ") is not implemented.");
                }
                it.remove();
            }
        }
    }

    public void triggerAllVariableListeners() {
        SolutionDescriptor solutionDescriptor = scoreDirector.getSolutionDescriptor();
        List<Object> entityList = scoreDirector.getWorkingEntityList();
        for (Object entity : entityList) {
            EntityDescriptor entityDescriptor = solutionDescriptor.findEntityDescriptorOrFail(entity.getClass());
            for (GenuineVariableDescriptor variableDescriptor : entityDescriptor.getGenuineVariableDescriptors()) {
                beforeVariableChanged(variableDescriptor, entity);
                // No change
                afterVariableChanged(variableDescriptor, entity);
            }
        }
    }

}
