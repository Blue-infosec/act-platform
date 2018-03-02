package no.mnemonic.act.platform.service.ti.delegates;

import no.mnemonic.act.platform.api.exceptions.AccessDeniedException;
import no.mnemonic.act.platform.api.exceptions.AuthenticationFailedException;
import no.mnemonic.act.platform.api.exceptions.InvalidArgumentException;
import no.mnemonic.act.platform.api.model.v1.Object;
import no.mnemonic.act.platform.api.request.v1.SearchObjectRequest;
import no.mnemonic.act.platform.api.service.v1.ResultSet;
import no.mnemonic.act.platform.dao.api.FactSearchCriteria;
import no.mnemonic.act.platform.dao.api.ObjectStatisticsCriteria;
import no.mnemonic.act.platform.dao.api.ObjectStatisticsResult;
import no.mnemonic.act.platform.dao.cassandra.entity.FactTypeEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectTypeEntity;
import no.mnemonic.act.platform.dao.elastic.document.ObjectDocument;
import no.mnemonic.act.platform.dao.elastic.document.SearchResult;
import no.mnemonic.act.platform.service.ti.TiFunctionConstants;
import no.mnemonic.act.platform.service.ti.TiRequestContext;
import no.mnemonic.act.platform.service.ti.TiSecurityContext;
import no.mnemonic.act.platform.service.ti.converters.ObjectConverter;
import no.mnemonic.act.platform.service.ti.converters.SearchObjectRequestConverter;
import no.mnemonic.commons.utilities.collections.CollectionUtils;
import no.mnemonic.commons.utilities.collections.ListUtils;
import no.mnemonic.commons.utilities.collections.SetUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ObjectSearchDelegate extends AbstractDelegate {

  public static ObjectSearchDelegate create() {
    return new ObjectSearchDelegate();
  }

  public ResultSet<Object> handle(SearchObjectRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException {
    TiSecurityContext.get().checkPermission(TiFunctionConstants.viewFactObjects);

    // Search for Objects in ElasticSearch and pick out all Object IDs.
    SearchResult<ObjectDocument> searchResult = TiRequestContext.get().getFactSearchManager().searchObjects(toCriteria(request));
    List<UUID> objectID = searchResult.getValues()
            .stream()
            .map(ObjectDocument::getId)
            .collect(Collectors.toList());

    // Return early if no Objects could be found because calculating the Fact statistics will fail without any Object IDs.
    if (CollectionUtils.isEmpty(objectID)) {
      return ResultSet.<Object>builder()
              .setCount(searchResult.getCount())
              .setLimit(searchResult.getLimit())
              .build();
    }

    // Use the Object IDs to retrieve the Fact statistics for all Objects from ElasticSearch.
    ObjectStatisticsCriteria criteria = ObjectStatisticsCriteria.builder()
            .setObjectID(SetUtils.set(objectID))
            .setCurrentUserID(TiSecurityContext.get().getCurrentUserID())
            .setAvailableOrganizationID(TiSecurityContext.get().getAvailableOrganizationID())
            .build();
    ObjectStatisticsResult statisticsResult = TiRequestContext.get().getFactSearchManager().calculateObjectStatistics(criteria);

    // Use the Object IDs to look up the authoritative data in Cassandra. This relies exclusively on access control
    // implemented in ElasticSearch. Explicitly checking access to each Object would be too expensive because this
    // requires fetching Facts for each Object. In addition, accidentally returning non-accessible Objects because
    // of an error in the ElasticSearch access control implementation will only leak the information that the Object
    // exists (plus potentially the Fact statistics) and will not give further access to any Facts.
    ObjectConverter converter = createObjectConverter(statisticsResult);
    List<Object> objects = ListUtils.list(TiRequestContext.get().getObjectManager().getObjects(objectID), converter);

    return ResultSet.<Object>builder()
            .setCount(searchResult.getCount())
            .setLimit(searchResult.getLimit())
            .setValues(objects)
            .build();
  }

  private FactSearchCriteria toCriteria(SearchObjectRequest request) {
    return SearchObjectRequestConverter.builder()
            .setCurrentUserIdSupplier(() -> TiSecurityContext.get().getCurrentUserID())
            .setAvailableOrganizationIdSupplier(() -> TiSecurityContext.get().getAvailableOrganizationID())
            .build()
            .apply(request);
  }

  private ObjectConverter createObjectConverter(ObjectStatisticsResult statistics) {
    return ObjectConverter.builder()
            .setObjectTypeConverter(id -> {
              ObjectTypeEntity type = TiRequestContext.get().getObjectManager().getObjectType(id);
              return TiRequestContext.get().getObjectTypeConverter().apply(type);
            })
            .setFactTypeConverter(id -> {
              FactTypeEntity type = TiRequestContext.get().getFactManager().getFactType(id);
              return TiRequestContext.get().getFactTypeConverter().apply(type);
            })
            .setFactStatisticsResolver(statistics::getStatistics)
            .build();
  }

}
