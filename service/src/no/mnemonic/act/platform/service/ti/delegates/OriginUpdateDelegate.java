package no.mnemonic.act.platform.service.ti.delegates;

import no.mnemonic.act.platform.api.exceptions.AccessDeniedException;
import no.mnemonic.act.platform.api.exceptions.AuthenticationFailedException;
import no.mnemonic.act.platform.api.exceptions.InvalidArgumentException;
import no.mnemonic.act.platform.api.exceptions.ObjectNotFoundException;
import no.mnemonic.act.platform.api.model.v1.Origin;
import no.mnemonic.act.platform.api.request.v1.UpdateOriginRequest;
import no.mnemonic.act.platform.dao.cassandra.OriginManager;
import no.mnemonic.act.platform.dao.cassandra.entity.OriginEntity;
import no.mnemonic.act.platform.service.ti.TiFunctionConstants;
import no.mnemonic.act.platform.service.ti.TiSecurityContext;
import no.mnemonic.act.platform.service.ti.converters.OriginConverter;
import no.mnemonic.act.platform.service.ti.resolvers.OriginResolver;
import no.mnemonic.commons.utilities.StringUtils;
import no.mnemonic.commons.utilities.collections.SetUtils;

import javax.inject.Inject;

public class OriginUpdateDelegate implements Delegate {

  private final TiSecurityContext securityContext;
  private final OriginManager originManager;
  private final OriginResolver originResolver;
  private final OriginConverter originConverter;

  @Inject
  public OriginUpdateDelegate(TiSecurityContext securityContext,
                              OriginManager originManager,
                              OriginResolver originResolver,
                              OriginConverter originConverter) {
    this.securityContext = securityContext;
    this.originManager = originManager;
    this.originResolver = originResolver;
    this.originConverter = originConverter;
  }

  public Origin handle(UpdateOriginRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    OriginEntity entity = fetchExistingOrigin(request);

    // Check that user is allowed to access/update the Origin.
    securityContext.checkReadPermission(entity);
    checkUpdatePermission(entity);

    // Check that Origin isn't deleted.
    assertNotDeleted(entity);

    if (request.getOrganization() != null) {
      // Check that the requested organization exists.
      assertOrganizationExists(request);
      // Check update permission for requested organization.
      securityContext.checkPermission(TiFunctionConstants.updateOrigins, request.getOrganization());
      entity.setOrganizationID(request.getOrganization());
    }

    if (!StringUtils.isBlank(request.getName())) {
      // Check that another Origin with the same name doesn't exist.
      assertOriginNotExists(request);
      entity.setName(request.getName());
    }

    if (!StringUtils.isBlank(request.getDescription())) {
      entity.setDescription(request.getDescription());
    }

    if (request.getTrust() != null) {
      entity.setTrust(request.getTrust());
    }

    entity = originManager.saveOrigin(entity);
    return originConverter.apply(entity);
  }

  private OriginEntity fetchExistingOrigin(UpdateOriginRequest request) throws ObjectNotFoundException {
    OriginEntity entity = originResolver.apply(request.getId());
    if (entity == null) {
      throw new ObjectNotFoundException(String.format("Origin with id = %s does not exist.", request.getId()),
              "origin.not.exist", "id", request.getId().toString());
    }
    return entity;
  }

  private void checkUpdatePermission(OriginEntity entity) throws AccessDeniedException, AuthenticationFailedException {
    if (entity.getOrganizationID() != null) {
      // Check specific update permission for the Origin.
      securityContext.checkPermission(TiFunctionConstants.updateOrigins, entity.getOrganizationID());
    } else {
      // Only check general update permission.
      securityContext.checkPermission(TiFunctionConstants.updateOrigins);
    }
  }

  private void assertNotDeleted(OriginEntity entity) throws InvalidArgumentException {
    if (SetUtils.set(entity.getFlags()).contains(OriginEntity.Flag.Deleted)) {
      throw new InvalidArgumentException()
              .addValidationError(String.format("Not allowed to update deleted Origin (id = %s).", entity.getId()),
                      "origin.update.deleted", "id", entity.getId().toString());
    }
  }

  private void assertOriginNotExists(UpdateOriginRequest request) throws InvalidArgumentException {
    // It's not allowed that two Origins have the same name.
    if (originManager.getOrigin(request.getName()) != null) {
      throw new InvalidArgumentException()
              .addValidationError(String.format("Origin with name = %s already exists.", request.getName()),
                      "origin.exist", "name", request.getName());
    }
  }

  private void assertOrganizationExists(UpdateOriginRequest request) throws InvalidArgumentException {
    // This just verifies that the user has somehow access to the requested organization.
    // More specific access control must be done afterwards.
    if (!securityContext.getAvailableOrganizationID().contains(request.getOrganization())) {
      throw new InvalidArgumentException()
              .addValidationError(String.format("Organization with id = %s does not exist.", request.getOrganization()),
                      "organization.not.exist", "organization", request.getOrganization().toString());
    }
  }
}
