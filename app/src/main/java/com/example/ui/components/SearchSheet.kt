package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.BilingualName
import com.example.data.model.DistanceKind
import com.example.data.model.EntryLegalityStatus
import com.example.data.model.FoundRoadMatch
import com.example.data.model.FoundRoadRelation
import com.example.data.model.PinSortOrder
import com.example.data.model.SavedPin
import com.example.data.model.SearchPlaceResult
import com.example.data.model.SearchScope
import com.example.data.model.UnitSystem
import com.example.ui.RoadHudUiState
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.GlassTokens
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.RoadColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBottomSheet(
  uiState: RoadHudUiState,
  onQueryChange: (String) -> Unit,
  onExecuteSearch: () -> Unit,
  onSelectPlace: (SearchPlaceResult) -> Unit,
  onDismiss: () -> Unit,
  isLandscape: Boolean = false,
  maxSheetHeight: Dp = 420.dp,
  onSelectScope: (SearchScope) -> Unit = {},
  onSelectFoundMatch: (FoundRoadMatch) -> Unit = {},
  onSelectRecentQuery: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val isDark = LocalRoadColors.current.isDark
  if (isLandscape) {
    GlassSurface(
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
      highOpacity = true,
      modifier =
        modifier
          .widthIn(max = 560.dp)
          .fillMaxWidth()
          .heightIn(max = maxSheetHeight)
          .testTag("search_anchored_sheet"),
    ) {
      SearchSheetContent(
        uiState = uiState,
        onQueryChange = onQueryChange,
        onExecuteSearch = onExecuteSearch,
        onSelectPlace = onSelectPlace,
        onSelectScope = onSelectScope,
        onSelectFoundMatch = onSelectFoundMatch,
        onSelectRecentQuery = onSelectRecentQuery,
        onDismiss = onDismiss,
        unitSystem = uiState.effectiveUnitSystem,
        showCloseButton = true,
      )
    }
  } else {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = if (isDark) BottomSheetDefaults.ContainerColor else GlassTokens.LightSolidSurface,
    ) {
      SearchSheetContent(
        uiState = uiState,
        onQueryChange = onQueryChange,
        onExecuteSearch = onExecuteSearch,
        onSelectPlace = onSelectPlace,
        onSelectScope = onSelectScope,
        onSelectFoundMatch = onSelectFoundMatch,
        onSelectRecentQuery = onSelectRecentQuery,
        onDismiss = onDismiss,
        unitSystem = uiState.effectiveUnitSystem,
        showCloseButton = false,
      )
    }
  }
}

@Composable
private fun SearchSheetContent(
  uiState: RoadHudUiState,
  onQueryChange: (String) -> Unit,
  onExecuteSearch: () -> Unit,
  onSelectPlace: (SearchPlaceResult) -> Unit,
  onSelectScope: (SearchScope) -> Unit,
  onSelectFoundMatch: (FoundRoadMatch) -> Unit,
  onSelectRecentQuery: (String) -> Unit,
  onDismiss: () -> Unit,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  showCloseButton: Boolean,
) {
  val roadColors = LocalRoadColors.current
  val searchInteraction = rememberPressHapticInteractionSource()
  val closeInteraction = rememberPressHapticInteractionSource()

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = uiState.searchQuery,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onExecuteSearch() }),
        trailingIcon = {
          IconButton(
            onClick = {
              TouchTelemetry.recordControlHandled("search_submit_button")
              onExecuteSearch()
            },
            interactionSource = searchInteraction,
            modifier = Modifier.size(48.dp).testTag("search_submit_button"),
          ) {
            Icon(
              Icons.Default.Search,
              contentDescription = stringResource(R.string.action_search),
              modifier = Modifier.innerPressScale(searchInteraction),
            )
          }
        },
        modifier = Modifier.weight(1f).testTag("search_query_input"),
      )
      if (showCloseButton) {
        IconButton(
          onClick = {
            TouchTelemetry.recordControlHandled("search_close_button")
            onDismiss()
          },
          interactionSource = closeInteraction,
          modifier = Modifier.size(48.dp).testTag("search_close_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            modifier = Modifier.innerPressScale(closeInteraction),
          )
        }
      }
    }

    // Scope selector chips: [On my road | Everywhere]
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val onRoadSelected = uiState.searchScope == SearchScope.ON_MY_ROAD
      FilterChip(
        selected = onRoadSelected,
        onClick = {
          TouchTelemetry.recordControlHandled("search_scope_on_road")
          onSelectScope(SearchScope.ON_MY_ROAD)
        },
        label = {
          Text(
            text = stringResource(R.string.search_scope_on_road),
            fontWeight = if (onRoadSelected) FontWeight.Bold else FontWeight.Medium,
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Route,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = RoadColors.PinPurple.copy(alpha = 0.18f),
          selectedLabelColor = RoadColors.PinPurple,
          selectedLeadingIconColor = RoadColors.PinPurple,
        ),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("search_scope_on_road"),
      )
      val everywhereSelected = uiState.searchScope == SearchScope.EVERYWHERE
      FilterChip(
        selected = everywhereSelected,
        onClick = {
          TouchTelemetry.recordControlHandled("search_scope_everywhere")
          onSelectScope(SearchScope.EVERYWHERE)
        },
        label = {
          Text(
            text = stringResource(R.string.search_scope_everywhere),
            fontWeight = if (everywhereSelected) FontWeight.Bold else FontWeight.Medium,
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("search_scope_everywhere"),
      )
    }

    // Subtle caution banner when vehicle is moving (> 8 km/h)
    if (uiState.showSearchSpeedCaution) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(roadColors.unverifiedBg)
            .border(1.dp, roadColors.unverifiedText.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("search_moving_caution"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Warning,
          contentDescription = null,
          tint = roadColors.unverifiedText,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = stringResource(R.string.search_moving_caution),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = roadColors.unverifiedText,
        )
      }
    }

    // Recent-10 searches quick-access row when query is blank
    if (uiState.searchQuery.isBlank() && uiState.settings.recentSearches.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = stringResource(R.string.search_recent_title),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          itemsIndexed(uiState.settings.recentSearches) { idx, recent ->
            SuggestionChip(
              onClick = {
                TouchTelemetry.recordControlHandled("recent_search_chip_$idx")
                onSelectRecentQuery(recent)
              },
              icon = {
                Icon(
                  imageVector = Icons.Default.History,
                  contentDescription = null,
                  modifier = Modifier.size(15.dp),
                )
              },
              label = { Text(text = recent, maxLines = 1) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("recent_search_chip_$idx"),
            )
          }
        }
      }
    }

    val showLocalHits = uiState.localFoundResults.isNotEmpty()
    val showRemoteHits = uiState.searchScope == SearchScope.EVERYWHERE && uiState.searchResults.isNotEmpty()

    if (uiState.isSearchingPlaces && !showLocalHits) {
      Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else if (!showLocalHits && !showRemoteHits) {
      Text(
        text =
          if (uiState.searchQuery.isBlank()) stringResource(R.string.search_empty)
          else stringResource(R.string.search_no_results),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (showLocalHits) {
          items(uiState.localFoundResults, key = { "local_${it.id}" }) { match ->
            FoundRoadResultRow(
              match = match,
              unitSystem = unitSystem,
              onClick = { onSelectFoundMatch(match) },
            )
            HorizontalDivider(color = roadColors.divider)
          }
        }
        if (showRemoteHits) {
          items(uiState.searchResults, key = { "remote_${it.placeId}" }) { place ->
            SearchResultRow(place = place, unitSystem = unitSystem, onClick = { onSelectPlace(place) })
            HorizontalDivider(color = roadColors.divider)
          }
        }
      }
    }

    if (!showCloseButton) {
      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@Composable
private fun FoundRoadResultRow(
  match: FoundRoadMatch,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  onClick: () -> Unit,
) {
  val tag = "found_result_${match.id}"
  val interactionSource = rememberPressHapticInteractionSource()
  val isPressed by interactionSource.collectIsPressedAsState()

  Surface(
    onClick = {
      TouchTelemetry.recordControlHandled(tag)
      onClick()
    },
    interactionSource = interactionSource,
    color = if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag(tag),
  ) {
    Row(
      modifier =
        Modifier
          .innerPressScale(interactionSource)
          .fillMaxWidth()
          .padding(vertical = 10.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = if (match.isStreet) Icons.Default.Route else Icons.Default.Place,
        contentDescription = match.placeType,
        tint = RoadColors.PinPurple,
        modifier = Modifier.size(28.dp),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Driving safety: >= 24sp primary result title for glanceability
        BilingualNameStack(
          name = match.name,
          fallbackText = match.name.primaryText ?: match.name.ref ?: match.placeType,
          arabicFontSize = 24.sp,
          englishFontSize = 24.sp,
          arabicWeight = FontWeight.Bold,
          englishWeight = FontWeight.Bold,
          maxLinesPerScript = 2,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          FoundRelationBadge(relation = match.relation, distanceMeters = match.alongRoadDistanceMeters, unitSystem = unitSystem)
          EntryLegalityBadge(status = match.entryStatus)
        }
      }

      DistanceMetricColumn(
        distanceMeters = match.alongRoadDistanceMeters,
        distanceKind = match.distanceKind,
        unitSystem = unitSystem,
      )
    }
  }
}

@Composable
private fun FoundRelationBadge(
  relation: FoundRoadRelation,
  distanceMeters: Double?,
  unitSystem: UnitSystem,
) {
  val label =
    when (relation) {
      FoundRoadRelation.AHEAD -> stringResource(R.string.relation_ahead)
      FoundRoadRelation.PARALLEL -> stringResource(R.string.relation_parallel)
      FoundRoadRelation.BEHIND -> {
        val dText = distanceMeters?.let { formatRoadDistance(it, unitSystem) } ?: ""
        if (dText.isNotEmpty()) stringResource(R.string.relation_behind_passed_format, dText)
        else stringResource(R.string.dir_behind)
      }
    }
  Text(
    text = label,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    color = RoadColors.PinPurple,
    modifier =
      Modifier
        .clip(CircleShape)
        .background(RoadColors.PinPurple.copy(alpha = 0.14f))
        .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}

@Composable
private fun EntryLegalityBadge(status: EntryLegalityStatus) {
  val roadColors = LocalRoadColors.current
  val (label, icon, fg, bg) =
    when (status) {
      EntryLegalityStatus.OPEN ->
        QuadrupleEntry(stringResource(R.string.entry_open), Icons.Default.CheckCircle, roadColors.openText, roadColors.openBg)
      EntryLegalityStatus.INDIRECT ->
        QuadrupleEntry(stringResource(R.string.entry_indirect), Icons.Default.TurnRight, roadColors.unverifiedText, roadColors.unverifiedBg)
      EntryLegalityStatus.BLOCKED ->
        QuadrupleEntry(stringResource(R.string.entry_blocked), Icons.Default.Close, roadColors.blockedText, roadColors.blockedBg)
      EntryLegalityStatus.UNVERIFIED ->
        QuadrupleEntry(stringResource(R.string.entry_unverified), Icons.Default.HelpOutline, roadColors.unverifiedText, roadColors.unverifiedBg)
    }
  Row(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(bg)
        .border(1.dp, fg.copy(alpha = 0.30f), CircleShape)
        .padding(horizontal = 7.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(11.dp))
    Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  }
}

private data class QuadrupleEntry(val label: String, val icon: ImageVector, val fg: Color, val bg: Color)

@Composable
private fun SearchResultRow(
  place: SearchPlaceResult,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  onClick: () -> Unit,
) {
  val bilingual =
    BilingualName(
      arabic = place.nameAr?.takeIf { it.isNotBlank() },
      english = place.nameEn?.takeIf { it.isNotBlank() } ?: place.primaryName.takeIf { it != place.nameAr },
      ref = null,
    )
  val tag = "search_result_${place.placeId}"
  val interactionSource = rememberPressHapticInteractionSource()
  val isPressed by interactionSource.collectIsPressedAsState()

  Surface(
    onClick = {
      TouchTelemetry.recordControlHandled(tag)
      onClick()
    },
    interactionSource = interactionSource,
    color = if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag(tag),
  ) {
    Row(
      modifier =
        Modifier
          .innerPressScale(interactionSource)
          .fillMaxWidth()
          .padding(vertical = 10.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = if (place.isStreet) Icons.Default.Route else Icons.Default.Place,
        contentDescription = place.placeType,
        tint = RoadColors.PinPurple,
        modifier = Modifier.size(26.dp),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BilingualNameStack(
          name = bilingual,
          fallbackText = place.primaryName,
          arabicFontSize = 24.sp,
          englishFontSize = 20.sp,
          arabicWeight = FontWeight.SemiBold,
          englishWeight = FontWeight.Medium,
          maxLinesPerScript = 2,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = place.placeType,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = RoadColors.PinPurple,
            modifier =
              Modifier
                .clip(CircleShape)
                .background(RoadColors.PinPurple.copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
          )
          if (!place.secondaryName.isNullOrBlank() && place.secondaryName != place.nameAr && place.secondaryName != place.nameEn) {
            Text(
              text = place.secondaryName,
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }

      DistanceMetricColumn(
        distanceMeters = place.distanceMeters,
        distanceKind = place.distanceKind,
        unitSystem = unitSystem,
      )
    }
  }
}

@Composable
private fun DistanceMetricColumn(
  distanceMeters: Double?,
  distanceKind: DistanceKind,
  unitSystem: UnitSystem = UnitSystem.METRIC,
) {
  Column(horizontalAlignment = Alignment.End) {
    when (distanceKind) {
      DistanceKind.LOADING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      DistanceKind.UNAVAILABLE -> {
        Text(
          text = stringResource(R.string.distance_label_unavailable),
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = LocalRoadColors.current.unverifiedText,
        )
      }
      DistanceKind.ROAD_SHORTEST,
      DistanceKind.FASTEST_ROUTE, -> {
        val distVal =
          distanceMeters?.let { formatRoadDistance(it, unitSystem) }
            ?: stringResource(R.string.distance_label_unavailable)
        Text(
          text = distVal,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text =
            if (distanceKind == DistanceKind.ROAD_SHORTEST) {
              stringResource(R.string.distance_label_road)
            } else {
              stringResource(R.string.distance_label_fastest)
            },
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Small callout opened when tapping a saved pin or street on the map:
 * name, type, road distance, "Route" / "Clear route" button, and "Remove" button.
 */
@Composable
fun PinCalloutCard(
  pin: SavedPin,
  isRouteActive: Boolean,
  isLoadingRoute: Boolean,
  onToggleRoute: () -> Unit,
  onRemove: () -> Unit,
  onDismiss: () -> Unit,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  modifier: Modifier = Modifier,
) {
  val closeInteraction = rememberPressHapticInteractionSource()
  val routeInteraction = rememberPressHapticInteractionSource()
  val removeInteraction = rememberPressHapticInteractionSource()

  GlassSurface(
    modifier = modifier.fillMaxWidth().testTag("pin_callout_card"),
    shape = RoundedCornerShape(22.dp),
    highOpacity = true,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (pin.isStreet) Icons.Default.Route else Icons.Default.Place,
          contentDescription = pin.placeType,
          tint = RoadColors.PinPurple,
          modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          BilingualNameStack(
            name = pin.toBilingualName(),
            fallbackText = pin.primaryName,
            arabicFontSize = 17.sp,
            englishFontSize = 13.sp,
            arabicWeight = FontWeight.Bold,
            englishWeight = FontWeight.Medium,
            maxLinesPerScript = 2,
          )
          val distLabel =
            when (pin.distanceKind) {
              DistanceKind.ROAD_SHORTEST ->
                "${pin.distanceMeters?.let { formatRoadDistance(it, unitSystem) } ?: ""} · ${stringResource(R.string.distance_label_road)}"
              DistanceKind.FASTEST_ROUTE ->
                "${pin.distanceMeters?.let { formatRoadDistance(it, unitSystem) } ?: ""} · ${stringResource(R.string.distance_label_fastest)}"
              DistanceKind.UNAVAILABLE -> stringResource(R.string.distance_label_unavailable)
              DistanceKind.LOADING -> stringResource(R.string.status_loading_graph)
            }
          Text(
            text = "${pin.placeType} · $distLabel",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
        }
        IconButton(
          onClick = {
            TouchTelemetry.recordControlHandled("pin_callout_close_button")
            onDismiss()
          },
          interactionSource = closeInteraction,
          modifier = Modifier.size(48.dp).testTag("pin_callout_close_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            modifier = Modifier.innerPressScale(closeInteraction).size(18.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = {
            TouchTelemetry.recordControlHandled("pin_callout_route_button")
            onToggleRoute()
          },
          interactionSource = routeInteraction,
          shape = RoundedCornerShape(18.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (isRouteActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
              contentColor =
                if (isRouteActive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier = Modifier.heightIn(min = 48.dp).testTag("pin_callout_route_button"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(routeInteraction),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (isLoadingRoute && isRouteActive) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            Text(
              text =
                if (isRouteActive) stringResource(R.string.action_clear_route)
                else stringResource(R.string.action_route),
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(start = 5.dp),
            )
          }
        }

        OutlinedButton(
          onClick = {
            TouchTelemetry.recordControlHandled("pin_callout_remove_button")
            onRemove()
          },
          interactionSource = removeInteraction,
          shape = RoundedCornerShape(18.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          modifier = Modifier.heightIn(min = 48.dp).testTag("pin_callout_remove_button"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(removeInteraction),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = null,
              tint = LocalRoadColors.current.blockedText,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = stringResource(R.string.action_remove),
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              color = LocalRoadColors.current.blockedText,
              modifier = Modifier.padding(start = 5.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * Callout card shown when a user selects a street or place from "Find on my road" local search.
 * Shows exact along-road distance, relation (Ahead / Passed X ago / Parallel), entry legality, Overview & explicit Route.
 */
@Composable
fun FoundRoadCalloutCard(
  found: FoundRoadMatch,
  isRouteActive: Boolean,
  isLoadingRoute: Boolean,
  onOverview: () -> Unit,
  onToggleRoute: () -> Unit,
  onClear: () -> Unit,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  modifier: Modifier = Modifier,
) {
  val closeInteraction = rememberPressHapticInteractionSource()
  val overviewInteraction = rememberPressHapticInteractionSource()
  val routeInteraction = rememberPressHapticInteractionSource()

  GlassSurface(
    modifier = modifier.fillMaxWidth().testTag("found_road_callout"),
    shape = RoundedCornerShape(22.dp),
    highOpacity = true,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (found.isStreet) Icons.Default.Route else Icons.Default.Place,
          contentDescription = found.placeType,
          tint = RoadColors.PinPurple,
          modifier = Modifier.padding(top = 2.dp).size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          BilingualNameStack(
            name = found.name,
            fallbackText = found.name.primaryText ?: found.name.ref ?: found.placeType,
            arabicFontSize = 19.sp,
            englishFontSize = 15.sp,
            arabicWeight = FontWeight.Bold,
            englishWeight = FontWeight.SemiBold,
            maxLinesPerScript = 2,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FoundRelationBadge(
              relation = found.relation,
              distanceMeters = found.alongRoadDistanceMeters,
              unitSystem = unitSystem,
            )
            EntryLegalityBadge(status = found.entryStatus)
          }
          val distLabel =
            when (found.distanceKind) {
              DistanceKind.ROAD_SHORTEST ->
                "${found.alongRoadDistanceMeters?.let { formatRoadDistance(it, unitSystem) } ?: ""} · ${stringResource(R.string.distance_label_road)}"
              DistanceKind.FASTEST_ROUTE ->
                "${found.alongRoadDistanceMeters?.let { formatRoadDistance(it, unitSystem) } ?: ""} · ${stringResource(R.string.distance_label_fastest)}"
              DistanceKind.UNAVAILABLE -> stringResource(R.string.distance_label_unavailable)
              DistanceKind.LOADING -> stringResource(R.string.status_loading_graph)
            }
          Text(
            text = "${found.placeType} · $distLabel",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
        }
        IconButton(
          onClick = {
            TouchTelemetry.recordControlHandled("found_callout_close_button")
            onClear()
          },
          interactionSource = closeInteraction,
          modifier = Modifier.size(48.dp).testTag("found_callout_close_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            modifier = Modifier.innerPressScale(closeInteraction).size(18.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = {
            TouchTelemetry.recordControlHandled("found_callout_overview_button")
            onOverview()
          },
          interactionSource = overviewInteraction,
          shape = RoundedCornerShape(18.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          modifier = Modifier.heightIn(min = 48.dp).testTag("found_callout_overview_button"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(overviewInteraction),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(
              text = stringResource(R.string.action_overview),
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(start = 5.dp),
            )
          }
        }

        Button(
          onClick = {
            TouchTelemetry.recordControlHandled("found_callout_route_button")
            onToggleRoute()
          },
          interactionSource = routeInteraction,
          shape = RoundedCornerShape(18.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (isRouteActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
              contentColor =
                if (isRouteActive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier = Modifier.heightIn(min = 48.dp).testTag("found_callout_route_button"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(routeInteraction),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (isLoadingRoute && isRouteActive) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            Text(
              text =
                if (isRouteActive) stringResource(R.string.action_clear_route)
                else stringResource(R.string.action_route),
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(start = 5.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * Saved Pins list bottom sheet (AR/EN name, type, matrix road distance, Route toggle, Remove, Sort, Clear all).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinsBottomSheet(
  pins: List<SavedPin>,
  sortOrder: PinSortOrder,
  activeRoutePinId: Long?,
  isLoadingRoute: Boolean,
  onToggleSort: () -> Unit,
  onFocusPin: (SavedPin) -> Unit,
  onToggleRoute: (Long) -> Unit,
  onRemovePin: (Long) -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit,
  isLandscape: Boolean = false,
  maxSheetHeight: Dp = 420.dp,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  modifier: Modifier = Modifier,
) {
  val isDark = LocalRoadColors.current.isDark
  if (isLandscape) {
    GlassSurface(
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
      highOpacity = true,
      modifier =
        modifier
          .widthIn(max = 560.dp)
          .fillMaxWidth()
          .heightIn(max = maxSheetHeight)
          .testTag("pins_anchored_sheet"),
    ) {
      PinsSheetContent(
        pins = pins,
        sortOrder = sortOrder,
        activeRoutePinId = activeRoutePinId,
        isLoadingRoute = isLoadingRoute,
        onToggleSort = onToggleSort,
        onFocusPin = onFocusPin,
        onToggleRoute = onToggleRoute,
        onRemovePin = onRemovePin,
        onClearAll = onClearAll,
        onDismiss = onDismiss,
        unitSystem = unitSystem,
        showCloseButton = true,
      )
    }
  } else {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = if (isDark) BottomSheetDefaults.ContainerColor else GlassTokens.LightSolidSurface,
    ) {
      PinsSheetContent(
        pins = pins,
        sortOrder = sortOrder,
        activeRoutePinId = activeRoutePinId,
        isLoadingRoute = isLoadingRoute,
        onToggleSort = onToggleSort,
        onFocusPin = onFocusPin,
        onToggleRoute = onToggleRoute,
        onRemovePin = onRemovePin,
        onClearAll = onClearAll,
        onDismiss = onDismiss,
        unitSystem = unitSystem,
        showCloseButton = false,
      )
    }
  }
}

@Composable
private fun PinsSheetContent(
  pins: List<SavedPin>,
  sortOrder: PinSortOrder,
  activeRoutePinId: Long?,
  isLoadingRoute: Boolean,
  onToggleSort: () -> Unit,
  onFocusPin: (SavedPin) -> Unit,
  onToggleRoute: (Long) -> Unit,
  onRemovePin: (Long) -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  showCloseButton: Boolean,
) {
  val roadColors = LocalRoadColors.current
  val sortInteraction = rememberPressHapticInteractionSource()
  val closeInteraction = rememberPressHapticInteractionSource()
  val clearAllInteraction = rememberPressHapticInteractionSource()

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pins_bottom_sheet"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "${stringResource(R.string.pins_title)} (${pins.size})",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilledTonalButton(
          onClick = {
            TouchTelemetry.recordControlHandled("pins_sort_toggle")
            onToggleSort()
          },
          interactionSource = sortInteraction,
          shape = CircleShape,
          border = BorderStroke(1.dp, roadColors.divider),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.heightIn(min = 48.dp).testTag("pins_sort_toggle"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(sortInteraction),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Default.SwapVert,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text =
                if (sortOrder == PinSortOrder.NEAREST) stringResource(R.string.pins_sort_nearest)
                else stringResource(R.string.pins_sort_newest),
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        if (showCloseButton) {
          IconButton(
            onClick = {
              TouchTelemetry.recordControlHandled("pins_close_button")
              onDismiss()
            },
            interactionSource = closeInteraction,
            modifier = Modifier.size(48.dp).testTag("pins_close_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_close),
              modifier = Modifier.innerPressScale(closeInteraction),
            )
          }
        }
      }
    }

    if (pins.isEmpty()) {
      Text(
        text = stringResource(R.string.pins_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items(pins, key = { it.id }) { pin ->
          val routeActive = activeRoutePinId == pin.id
          val rowTag = "pin_row_${pin.id}"
          val rowInteraction = rememberPressHapticInteractionSource()
          val routeInteraction = rememberPressHapticInteractionSource()
          val removeInteraction = rememberPressHapticInteractionSource()
          val shape = RoundedCornerShape(16.dp)

          Surface(
            onClick = {
              TouchTelemetry.recordControlHandled(rowTag)
              onFocusPin(pin)
            },
            interactionSource = rowInteraction,
            shape = shape,
            color = roadColors.cardElevated,
            border = if (!roadColors.isDark) BorderStroke(1.dp, GlassTokens.LightInnerBorder) else null,
            modifier = Modifier.fillMaxWidth().testTag(rowTag),
          ) {
            Column(
              modifier =
                Modifier
                  .innerPressScale(rowInteraction)
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = if (pin.isStreet) Icons.Default.Route else Icons.Default.Place,
                  contentDescription = pin.placeType,
                  tint = RoadColors.PinPurple,
                  modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  BilingualNameStack(
                    name = pin.toBilingualName(),
                    fallbackText = pin.primaryName,
                    arabicFontSize = 15.sp,
                    englishFontSize = 12.sp,
                    arabicWeight = FontWeight.SemiBold,
                    englishWeight = FontWeight.Medium,
                    maxLinesPerScript = 2,
                  )
                  Text(
                    text = pin.placeType,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RoadColors.PinPurple,
                  )
                }
                DistanceMetricColumn(
                  distanceMeters = pin.distanceMeters,
                  distanceKind = pin.distanceKind,
                  unitSystem = unitSystem,
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                TextButton(
                  onClick = {
                    TouchTelemetry.recordControlHandled("pin_row_route_${pin.id}")
                    onToggleRoute(pin.id)
                  },
                  interactionSource = routeInteraction,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("pin_row_route_${pin.id}"),
                ) {
                  Row(
                    modifier = Modifier.innerPressScale(routeInteraction),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    if (isLoadingRoute && routeActive) {
                      CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                      Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                      text =
                        if (routeActive) stringResource(R.string.action_clear_route)
                        else stringResource(R.string.action_route),
                      fontSize = 13.sp,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.padding(start = 4.dp),
                    )
                  }
                }
                IconButton(
                  onClick = {
                    TouchTelemetry.recordControlHandled("pin_row_remove_${pin.id}")
                    onRemovePin(pin.id)
                  },
                  interactionSource = removeInteraction,
                  modifier = Modifier.size(48.dp).testTag("pin_row_remove_${pin.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = roadColors.blockedText,
                    modifier = Modifier.innerPressScale(removeInteraction),
                  )
                }
              }
            }
          }
        }
      }

      OutlinedButton(
        onClick = {
          TouchTelemetry.recordControlHandled("pins_clear_all_button")
          onClearAll()
        },
        interactionSource = clearAllInteraction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pins_clear_all_button"),
      ) {
        Text(
          text = stringResource(R.string.pins_clear_all),
          color = roadColors.blockedText,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.innerPressScale(clearAllInteraction),
        )
      }
    }

    if (!showCloseButton) {
      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}
