package org.apereo.cas.services;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * This is {@link RegisteredServiceAccessStrategyActivationCriteria}.
 *
 * @author Misagh Moayyed
 * @since 7.0.0
 */
@FunctionalInterface
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public interface RegisteredServiceAccessStrategyActivationCriteria extends Serializable {
    /**
     * Should activate policy based on this request?
     *
     * @param request the request
     * @return true/false
     */
    boolean shouldActivate(RegisteredServiceAccessStrategyRequest request);

    /**
     * Gets deactivation status.
     *
     * @return the deactivation status
     */
    default boolean shouldAllowIfInactive() {
        return true;
    }

    /**
     * Always activate criteria.
     *
     * @return the registered service access strategy activation criteria
     */
    static RegisteredServiceAccessStrategyActivationCriteria always() {
        return request -> true;
    }
}
