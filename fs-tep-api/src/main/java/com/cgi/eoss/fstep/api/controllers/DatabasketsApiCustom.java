package com.cgi.eoss.fstep.api.controllers;

import com.cgi.eoss.fstep.model.Databasket;
import com.cgi.eoss.fstep.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DatabasketsApiCustom {

    Page<Databasket> findByFilterOnly(String filter, Pageable pageable);

    Page<Databasket> findByFilterAndOwner(String filter, User user, Pageable pageable);

    Page<Databasket> findByFilterAndNotOwner(String filter, User user, Pageable pageable);
}
