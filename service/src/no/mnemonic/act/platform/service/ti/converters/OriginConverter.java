package no.mnemonic.act.platform.service.ti.converters;

import no.mnemonic.act.platform.api.model.v1.Organization;
import no.mnemonic.act.platform.api.model.v1.Origin;
import no.mnemonic.act.platform.dao.cassandra.entity.OriginEntity;
import no.mnemonic.commons.utilities.ObjectUtils;
import no.mnemonic.commons.utilities.collections.SetUtils;

import javax.inject.Inject;
import java.util.function.Function;

public class OriginConverter implements Function<OriginEntity, Origin> {

  private final NamespaceByIdConverter namespaceConverter;
  private final OrganizationByIdConverter organizationConverter;

  @Inject
  public OriginConverter(NamespaceByIdConverter namespaceConverter,
                         OrganizationByIdConverter organizationConverter) {
    this.namespaceConverter = namespaceConverter;
    this.organizationConverter = organizationConverter;
  }

  @Override
  public Origin apply(OriginEntity entity) {
    if (entity == null) return null;
    return Origin.builder()
            .setId(entity.getId())
            .setNamespace(namespaceConverter.apply(entity.getNamespaceID()))
            .setOrganization(ObjectUtils.ifNotNull(organizationConverter.apply(entity.getOrganizationID()), Organization::toInfo))
            .setName(entity.getName())
            .setDescription(entity.getDescription())
            .setTrust(entity.getTrust())
            .setType(ObjectUtils.ifNotNull(entity.getType(), type -> Origin.Type.valueOf(type.name())))
            .setFlags(SetUtils.set(entity.getFlags(), flag -> Origin.Flag.valueOf(flag.name())))
            .build();
  }
}
