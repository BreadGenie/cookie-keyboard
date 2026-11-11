package org.futo.inputmethod.latin.uix.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlin.math.pow
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSettingBlocking

@Composable
fun ScreenTitle(title: String, showBack: Boolean = false, navController: NavHostController? = LocalNavController.current ?: rememberNavController()) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
        verticalAlignment = CenterVertically
    ) {
        if (showBack) {
            IconButton(onClick = { navController?.navigateUp() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Navigate back")
            }
        }
        Text(
            title,
            style = if (showBack) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 16.dp).semantics { heading() }
        )
    }
}

/** A non-interactive category label; decorations stay outside search results. */
@Composable
fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)
            .semantics { heading() }
    )
}

@Composable
fun ScreenTitleWithIcon(title: String, painter: Painter) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(16.dp))

        Icon(painter, contentDescription = null, modifier = Modifier.align(CenterVertically))
        Spacer(modifier = Modifier.width(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier
            .align(CenterVertically)
            .padding(0.dp, 16.dp))
    }
}

@Composable
@Preview
fun Tip(text: String = "This is an example tip") {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp), shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
@Preview
fun WarningTip(text: String = "This is an example tip") {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp), shape = MaterialTheme.shapes.medium
    ) {

        Text(
            buildAnnotatedString {
                appendInlineContent("icon")
                append(' ')
                append(text)
            },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            inlineContent = mapOf(
                "icon" to InlineTextContent(
                    Placeholder(
                        width = with(LocalDensity.current) { 24.dp.toPx().toSp() },
                        height = with(LocalDensity.current) { 24.dp.toPx().toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ){
                    Icon(Icons.Default.Warning, contentDescription = null)
                }
            ))
    }
}

@Composable
fun SpacedColumn(gap: Dp, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = horizontalAlignment) {
        content()
    }
}

@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    disabled: Boolean = false,
    subcontent: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    onSubmenuNavigate: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val textColor = when(LocalContentColor.current) {
        MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.onSecondary,
        MaterialTheme.colorScheme.onTertiary -> LocalContentColor.current

        else -> MaterialTheme.colorScheme.onSurface
    }

    val subTextColor = when(textColor) {
        MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.onSecondary,
        MaterialTheme.colorScheme.onTertiary -> textColor.copy(alpha = 0.6f)

        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = if (compact) 48.dp else 64.dp)
            .let {
                val action = onSubmenuNavigate ?: onClick
                if (action != null) it.clickable(enabled = !disabled, onClick = action) else it
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.padding(end = 16.dp).width(48.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Column(Modifier.weight(1f).alpha(if (disabled) 0.38f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subTextColor)
            } else {
                subcontent?.invoke()
            }
        }
        if (onSubmenuNavigate != null) {
            VerticalDivider(Modifier.padding(horizontal = 16.dp).height(32.dp), color = MaterialTheme.colorScheme.outlineVariant)
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun SettingToggleRaw(
    title: String,
    enabled: Boolean,
    setValue: (Boolean) -> Unit,
    subtitle: String? = null,
    disabled: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onSubmenuNavigate: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    SettingItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        disabled = disabled,
        modifier = if (onSubmenuNavigate == null) {
            Modifier.toggleable(value = enabled, enabled = !disabled, role = Role.Switch, onValueChange = setValue)
        } else Modifier,
        onSubmenuNavigate = onSubmenuNavigate,
        compact = compact
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = if (onSubmenuNavigate == null) null else setValue,
            enabled = !disabled,
            modifier = if (onSubmenuNavigate != null) Modifier.semantics { contentDescription = title } else Modifier
        )
    }
}

@Composable
fun SettingToggleDataStoreItem(
    title: String,
    dataStoreItem: DataStoreItem<Boolean>,
    subtitle: String? = null,
    disabledSubtitle: String? = null,
    disabled: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onSubmenuNavigate: (() -> Unit)? = null,
) {
    val (enabled, setValue) = dataStoreItem

    val subtitleValue = if (!enabled && disabledSubtitle != null) {
        disabledSubtitle
    } else {
        subtitle
    }

    SettingToggleRaw(title, enabled, { setValue(it) }, subtitleValue, disabled, icon, onSubmenuNavigate)
}

@Composable
fun SettingToggleDataStore(
    title: String,
    setting: SettingsKey<Boolean>,
    subtitle: String? = null,
    disabledSubtitle: String? = null,
    disabled: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onSubmenuNavigate: (() -> Unit)? = null,
) {
    key(setting) {
        SettingToggleDataStoreItem(
            title,
            useDataStore(setting.key, setting.default),
            subtitle,
            disabledSubtitle,
            disabled,
            icon,
            onSubmenuNavigate
        )
    }
}

@Composable
fun SettingToggleSharedPrefs(
    title: String,
    key: String,
    default: Boolean,
    subtitle: String? = null,
    disabledSubtitle: String? = null,
    disabled: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onSubmenuNavigate: (() -> Unit)? = null,
) {
    key(key) {
        SettingToggleDataStoreItem(
            title, useSharedPrefsBool(key, default), subtitle, disabledSubtitle, disabled, icon, onSubmenuNavigate
        )
    }
}

@Composable
fun<T> SettingRadio(
    title: String,
    options: List<T>,
    optionNames: List<String>,
    setting: DataStoreItem<T>,
    modifier: Modifier = Modifier,
    hints: List<@Composable () -> Unit>? = null,
    subcontent: List<@Composable () -> Unit>? = null,
) {
    ScreenTitle(title, showBack = false)
    Column(Modifier.selectableGroup()) {
        options.zip(optionNames).forEachIndexed { i, it ->
            SettingItem(
                title = it.second,
                icon = { RadioButton(selected = setting.value == it.first, onClick = null) },
                modifier = modifier.selectable(
                    selected = setting.value == it.first,
                    role = Role.RadioButton,
                    onClick = { setting.setValue(it.first) }
                ),
                subcontent = subcontent?.getOrNull(i)
            ) { hints?.getOrNull(i)?.invoke() }
        }
    }
}

@Composable
fun<T: Number> SettingSliderForDataStoreItem(
    title: String,
    item: DataStoreItem<T>,
    default: T,
    range: ClosedFloatingPointRange<Float>,
    transform: (Float) -> T,
    indicator: (T) -> String = { it.toString() },
    hardRange: ClosedFloatingPointRange<Float> = range,
    power: Float = 1.0f,
    subtitle: String? = null,
    steps: Int = 0,
) {
    val context = LocalContext.current

    val (value, setValue) = item
    var virtualValue by remember { mutableFloatStateOf(value.toFloat().let {
        if(it == Float.POSITIVE_INFINITY || it == Float.NEGATIVE_INFINITY) {
            it
        } else {
            it.pow(1.0f / power)
        }
    }) }
    var isTextFieldVisible by remember { mutableStateOf(false) }
    var hasTextFieldFocusedYet by remember { mutableStateOf(false) }
    var textFieldValue by remember(value) {
        val s = value.toString()
        mutableStateOf(TextFieldValue(
            s,
            selection = TextRange(0, s.length)
        ))
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isTextFieldVisible) {
        if(isTextFieldVisible) focusRequester.requestFocus()
    }

    Column {
        ScreenTitle(title, showBack = false)
        if(subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp, 0.dp))
        }
        Row(modifier = Modifier.padding(16.dp, 0.dp)) {
            if (isTextFieldVisible) {
                val apply = {
                    if(isTextFieldVisible) {
                        val number = textFieldValue.text.trim().toFloatOrNull()
                        val newValue = if (number != null) {
                            transform(number.coerceIn(hardRange))
                        } else {
                            default
                        }

                        setValue(newValue)
                        virtualValue = newValue.toFloat().pow(1.0f / power)

                        isTextFieldVisible = false
                        textFieldValue = TextFieldValue()
                    }
                }

                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier
                        .weight(0.33f)
                        .heightIn(min = 48.dp)
                        .align(CenterVertically)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) hasTextFieldFocusedYet = true
                            else if (!it.isFocused && hasTextFieldFocusedYet) apply()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            apply()
                        }
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            } else {
                Text(
                    text = indicator(value),
                    modifier = Modifier
                        .weight(0.33f)
                        .heightIn(min = 48.dp)
                        .align(Alignment.CenterVertically)
                        .clickable {
                            hasTextFieldFocusedYet = false
                            isTextFieldVisible = true
                        },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = virtualValue,
                onValueChange = {
                    virtualValue = it
                    setValue(transform(it.pow(power))) },
                valueRange = range.start.pow(1.0f / power) .. range.endInclusive.pow(1.0f / power),
                enabled = !isTextFieldVisible,
                modifier = Modifier.weight(1.0f),
                steps = steps
            )
        }
    }
}

@Composable
fun<T: Number> SettingSlider(
    title: String,
    setting: SettingsKey<T>,
    range: ClosedFloatingPointRange<Float>,
    transform: (Float) -> T,
    indicator: (T) -> String = { it.toString() },
    hardRange: ClosedFloatingPointRange<Float> = range,
    power: Float = 1.0f,
    subtitle: String? = null,
    steps: Int = 0
) {
    SettingSliderForDataStoreItem(
        title = title,
        item = useDataStore(setting, blocking = true),
        default = setting.default,
        range = range,
        transform = transform,
        indicator = indicator,
        hardRange = hardRange,
        power = power,
        subtitle = subtitle,
        steps = steps
    )
}

@Composable
fun SettingSliderSharedPrefsInt(
    title: String,
    key: String,
    default: Int,
    range: ClosedFloatingPointRange<Float>,
    transform: (Float) -> Int,
    indicator: (Int) -> String = { it.toString() },
    hardRange: ClosedFloatingPointRange<Float> = range,
    power: Float = 1.0f,
    subtitle: String? = null,
    steps: Int = 0
) {
    SettingSliderForDataStoreItem(
        title = title,
        item = useSharedPrefsInt(key, default),
        default = default,
        range = range,
        transform = transform,
        indicator = indicator,
        hardRange = hardRange,
        power = power,
        subtitle = subtitle,
        steps = steps
    )
}

@Composable
fun ScrollableList(modifier: Modifier = Modifier, spacing: Dp = 0.dp, horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable () -> Unit) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = horizontalAlignment
    ) {
        content()
    }
}

@Composable
fun SettingListLazy(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        content()
    }
}

enum class NavigationItemStyle {
    HomePrimary,
    HomeSecondary,
    HomeTertiary,
    MiscNoArrow,
    Misc,
    ExternalLink,
    Mail
}

@Composable
fun NavigationItem(title: String, style: NavigationItemStyle, navigate: () -> Unit, icon: Painter? = null, subtitle: String? = null, compact: Boolean = false) {
    SettingItem(
        title = title,
        subtitle = subtitle,
        onClick = navigate,
        compact = compact,
        icon = icon?.let { painter -> {
            val home = style == NavigationItemStyle.HomePrimary ||
                style == NavigationItemStyle.HomeSecondary || style == NavigationItemStyle.HomeTertiary
            Box(
                Modifier.size(40.dp).background(
                    if (home) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    MaterialTheme.shapes.medium
                ), contentAlignment = Alignment.Center
            ) {
                Icon(painter, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = if (home) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    ) {
        when (style) {
            NavigationItemStyle.Mail -> Icon(Icons.Default.Send, contentDescription = null)
            NavigationItemStyle.ExternalLink -> Icon(painterResource(R.drawable.external_link), contentDescription = null)
            else -> Icon(painterResource(R.drawable.chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingTextField(title: String, placeholder: String, field: SettingsKey<String>) {
    val context = LocalContext.current

    val personalDict = useDataStore(field)
    val textFieldValue = remember { mutableStateOf(context.getSettingBlocking(
        field.key, field.default)) }

    LaunchedEffect(textFieldValue.value) {
        personalDict.setValue(textFieldValue.value)
    }

    ScreenTitle(title)

    TextField(
        value = textFieldValue.value,
        onValueChange = {
            textFieldValue.value = it
        },
        placeholder = { Text(placeholder) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp, 4.dp),
    )
}

@Composable
fun CollapsibleSection(title: String, modifier: Modifier = Modifier, section: @Composable ColumnScope.() -> Unit) {
    val resources = LocalResources.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                expanded = !expanded
            }.padding(16.dp).semantics {
                stateDescription = resources.getString(
                    if(expanded)
                        R.string.setting_section_expanded
                    else
                        R.string.setting_section_collapsed
                )
                role = Role.Button
            }
        ) {
            RotatingChevronIcon(expanded, tint = LocalContentColor.current)

            Spacer(Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = LocalContentColor.current,
                modifier = Modifier.weight(1.0f)
            )
        }

        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                section()
            }
        }
    }
}

/** Material single-choice dialog shared by picker rows and standalone pickers. */
@Composable
private fun<T> SettingsChoiceDialog(
    title: String?,
    options: List<T>,
    selection: T?,
    onSet: (T) -> Unit,
    getDisplayName: (T) -> String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = selection == option, role = Role.RadioButton, onClick = {
                                onSet(option)
                                onDismiss()
                            }).padding(vertical = 12.dp),
                        verticalAlignment = CenterVertically
                    ) {
                        RadioButton(selected = selection == option, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(getDisplayName(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
fun<T> DropDownPicker(
    options: List<T>,
    selection: T?,
    onSet: (T) -> Unit,
    getDisplayName: (T) -> String,
    scrollableOptions: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = CenterVertically) {
            Text(selection?.let(getDisplayName).orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.chevron_down), contentDescription = null)
        }
    }
    if (expanded) SettingsChoiceDialog(null, options, selection, onSet, getDisplayName) { expanded = false }
}

@Composable
fun<T> DropDownPickerSettingItem(
    label: String,
    options: List<T>,
    selection: T?,
    onSet: (T) -> Unit,
    getDisplayName: (T) -> String,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingItem(
        title = label,
        subtitle = selection?.let(getDisplayName),
        onClick = { expanded = true },
        icon = icon,
        modifier = modifier
    ) {
        Icon(painterResource(R.drawable.chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) SettingsChoiceDialog(label, options, selection, onSet, getDisplayName) { expanded = false }
}

@Composable
fun RotatingChevronIcon(isExpanded: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) -180f else 0f
    )

    Icon(
        painter = painterResource(R.drawable.chevron_down),
        contentDescription = null,
        modifier = modifier.rotate(rotation),
        tint = tint
    )
}

@Composable
fun PrimarySettingToggleDataStoreItem(
    title: String,
    dataStoreItem: DataStoreItem<Boolean>,
) {
    val (enabled, setValue) = dataStoreItem

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
    ) {
        Row(
            Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = setValue)
                .heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Preview
@Composable
fun PreviewPrimarySetting() {
    PrimarySettingToggleDataStoreItem(
        "Enable",
        dataStoreItem = DataStoreItem(false, { error("") })
    )
}
