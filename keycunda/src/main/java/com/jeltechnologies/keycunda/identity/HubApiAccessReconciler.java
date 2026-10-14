package com.jeltechnologies.keycunda.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.jeltechnologies.keycunda.client.ClientRepository;

/**
 * At startup, grants Hub API access in Identity to every existing client whose audience includes it,
 * so clients created before the grant was automated (or while Identity was unreachable) catch up.
 * Failures are logged by the grantor and never stop Keycunda from starting.
 */
@Component
public class HubApiAccessReconciler implements ApplicationRunner {

    private final ClientRepository clientRepository;
    private final HubApiAccessGrantor grantor;

    public HubApiAccessReconciler(ClientRepository clientRepository, HubApiAccessGrantor grantor) {
        this.clientRepository = clientRepository;
        this.grantor = grantor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (grantor.isConfigured()) {
            grantor.reconcile(clientRepository.findAll().stream()
                    .map(c -> new HubApiAccessGrantor.ClientAudience(c.clientId(), c.audience())).toList());
        }
    }
}
