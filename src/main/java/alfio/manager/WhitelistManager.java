/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.model.modification.WhitelistItemModification;
import alfio.model.whitelist.Whitelist;
import alfio.model.whitelist.WhitelistConfiguration;
import alfio.model.whitelist.WhitelistItem;
import alfio.model.whitelist.WhitelistedTicket;
import alfio.repository.WhitelistRepository;
import ch.digitalfondue.npjt.AffectedRowCountAndKey;
import lombok.AllArgsConstructor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@AllArgsConstructor
@Transactional
@Component
public class WhitelistManager {

    private final WhitelistRepository whitelistRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Whitelist createNew(String name, String description, int organizationId) {
        AffectedRowCountAndKey<Integer> insert = whitelistRepository.insert(name, description, organizationId);
        return whitelistRepository.getById(insert.getKey());
    }

    public WhitelistConfiguration createConfiguration(int whitelistId,
                                                      int eventId,
                                                      Integer categoryId,
                                                      WhitelistConfiguration.Type type,
                                                      WhitelistConfiguration.MatchType matchType) {
        Objects.requireNonNull(whitelistRepository.getById(whitelistId), "Whitelist not found");
        AffectedRowCountAndKey<Integer> configuration = whitelistRepository.createConfiguration(whitelistId, eventId, categoryId, type, matchType);
        return whitelistRepository.getConfiguration(configuration.getKey());
    }

    public boolean isWhitelistConfiguredFor(int eventId, int categoryId) {
        return CollectionUtils.isNotEmpty(findConfigurations(eventId, categoryId));
    }

    public boolean isAllowed(String value, int eventId, int categoryId) {

        List<WhitelistConfiguration> configurations = findConfigurations(eventId, categoryId);
        if(CollectionUtils.isEmpty(configurations)) {
            return true;
        }
        WhitelistConfiguration configuration = configurations.get(0);
        return whitelistRepository.findItemByValueExactMatch(configuration.getId(), configuration.getWhitelistId(), StringUtils.trim(value)).isPresent();
    }

    private List<WhitelistConfiguration> findConfigurations(int eventId, int categoryId) {
        return whitelistRepository.findActiveConfigurationsFor(eventId, categoryId);
    }

    public int insertItems(int whitelistId, List<WhitelistItemModification> items) {
        MapSqlParameterSource[] params = items.stream()
            .map(i -> new MapSqlParameterSource("whitelistId", whitelistId).addValue("value", i.getValue()).addValue("description", i.getDescription()))
            .toArray(MapSqlParameterSource[]::new);
        return Arrays.stream(jdbcTemplate.batchUpdate(whitelistRepository.insertItemTemplate(), params)).sum();
    }

    public boolean confirmTicket(String value, int eventId, int categoryId, int ticketId) {
        List<WhitelistConfiguration> configurations = findConfigurations(eventId, categoryId);
        if(CollectionUtils.isEmpty(configurations)) {
            return true;
        }
        WhitelistConfiguration configuration = configurations.get(0);
        Optional<WhitelistItem> optionalItem = whitelistRepository.findItemByValueExactMatch(configuration.getId(), configuration.getWhitelistId(), StringUtils.trim(value));
        if(!optionalItem.isPresent()) {
            return false;
        }
        WhitelistItem item = optionalItem.get();
        boolean preventDuplication = configuration.getType() == WhitelistConfiguration.Type.ONCE_PER_VALUE;
        if(preventDuplication) {
            //reload and lock configuration
            configuration = whitelistRepository.getConfigurationForUpdate(configuration.getId());
            Optional<WhitelistedTicket> existing = whitelistRepository.findExistingWhitelistedTicket(item.getId(), configuration.getId());
            if(existing.isPresent()) {
                return false;
            }
        }
        whitelistRepository.insertWhitelistedTicket(item.getId(), configuration.getId(), ticketId, preventDuplication ? true : null);
        return true;
    }
}
