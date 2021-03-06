package com.cgi.eoss.fstep.api.controllers;

import com.cgi.eoss.fstep.security.FstepSecurityService;
import com.cgi.eoss.fstep.model.JobConfig;
import com.cgi.eoss.fstep.model.QJobConfig;
import com.cgi.eoss.fstep.model.QUser;
import com.cgi.eoss.fstep.persistence.dao.JobConfigDao;
import com.querydsl.core.types.dsl.NumberPath;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Getter
@Component
public class JobConfigsApiImpl extends BaseRepositoryApiImpl<JobConfig> {

    private final FstepSecurityService securityService;
    private final JobConfigDao dao;

    @Override
    public <S extends JobConfig> S save(S entity) {
        if (entity.getOwner() == null) {
            getSecurityService().updateOwnerWithCurrentUser(entity);
        }

        JobConfig existing = getDao().findOne(QJobConfig.jobConfig.owner.eq(entity.getOwner())
                .and(QJobConfig.jobConfig.service.eq(entity.getService()))
                .and(QJobConfig.jobConfig.inputs.eq(entity.getInputs())));
        if (existing != null) {
            entity.setId(existing.getId());
        }
        return getDao().save(entity);
    }

    @Override
    NumberPath<Long> getIdPath() {
        return QJobConfig.jobConfig.id;
    }

    @Override
    QUser getOwnerPath() {
        return QJobConfig.jobConfig.owner;
    }

    @Override
    Class<JobConfig> getEntityClass() {
        return JobConfig.class;
    }

}
