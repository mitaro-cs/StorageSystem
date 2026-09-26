<script lang="ts">
	import { onMount } from 'svelte';
	import { Check, ImagePlus, Monitor, Moon, Sun, Trash2 } from '@lucide/svelte';
	import {
		STYLES,
		currentStyle,
		currentTheme,
		setStyle,
		setTheme,
		type Style,
		type Theme
	} from '$lib/theme';
	import { DEFAULT_SAT, PRESETS, currentAccent, oklch, setAccent, type Accent } from '$lib/colors';
	import {
		ICONS,
		currentIcon,
		customBackground,
		removeCustomBackground,
		setCustomBackground,
		setIcon
	} from '$lib/looks';
	import { iconSrc, type AppIcon } from '$lib/appIcon.svelte';
	import { toast } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Оформление под себя: режим, один из пяти дизайнов, основной цвет и его насыщенность, своя
	// картинка на фоне и значок. Меняется сразу, хранится на этом устройстве.
	let theme = $state<Theme>('system');
	let style = $state<Style>('plain');
	let accent = $state<Accent>({ hue: null, sat: DEFAULT_SAT });
	let custom = $state(false);
	let bgImage = $state<string | null>(null);
	let icon = $state<AppIcon>('light');
	let fileInput: HTMLInputElement | undefined = $state();

	onMount(() => {
		theme = currentTheme();
		style = currentStyle();
		accent = currentAccent();
		custom = accent.hue !== null && !PRESETS.some((p) => p.hue === accent.hue);
		bgImage = customBackground();
		icon = currentIcon();
	});

	const modes: { value: Theme; label: string; icon: typeof Sun }[] = [
		{ value: 'system', label: 'Как в системе', icon: Monitor },
		{ value: 'light', label: 'Светлая', icon: Sun },
		{ value: 'dark', label: 'Тёмная', icon: Moon }
	];

	function pickTheme(t: Theme) {
		theme = t;
		setTheme(t);
	}

	function pickStyle(s: Style) {
		style = s;
		setStyle(s);
	}

	function pickPreset(hue: number | null) {
		custom = false;
		accent = { ...accent, hue };
		setAccent(accent);
	}

	function pickCustom() {
		custom = true;
		accent = { ...accent, hue: accent.hue ?? 200 };
		setAccent(accent);
	}

	/** Ползунки: без плавного перехода — цвет идёт за пальцем. */
	function slide(next: Partial<Accent>) {
		accent = { ...accent, ...next };
		setAccent(accent, false);
	}

	async function pickFile(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		try {
			if (!(await setCustomBackground(file))) {
				toast('Картинка не поместилась в память браузера — выберите поменьше', 'error');
				return;
			}
		} catch {
			toast('Эту картинку не открыть — выберите JPG, PNG или WebP', 'error');
			return;
		}
		bgImage = customBackground();
	}

	function dropBackground() {
		removeCustomBackground();
		bgImage = null;
	}

	function pickIcon(i: AppIcon) {
		icon = i;
		setIcon(i);
	}

	/** Образец цвета — ярким, как на кнопке. */
	const dot = (hue: number) => oklch(0.62, 0.19, hue);
	const satLabel = $derived(
		accent.sat < 25 ? 'спокойно' : accent.sat < 55 ? 'мягко' : accent.sat < 80 ? 'ярко' : 'сочно'
	);
</script>

<div class="picker">
	<div>
		<p class="label" id="mode-label">Режим</p>
		<div class="seg" role="radiogroup" aria-labelledby="mode-label">
			{#each modes as m (m.value)}
				<button
					type="button"
					role="radio"
					aria-checked={theme === m.value}
					class:on={theme === m.value}
					onclick={() => pickTheme(m.value)}
				>
					<m.icon size={16} />
					{m.label}
				</button>
			{/each}
		</div>
	</div>

	<div>
		<p class="label" id="style-label">Дизайн</p>
		<div class="designs" role="radiogroup" aria-labelledby="style-label">
			{#each STYLES as s (s.id)}
				<button
					type="button"
					role="radio"
					aria-checked={style === s.id}
					aria-label={s.label}
					class="design"
					class:on={style === s.id}
					onclick={() => pickStyle(s.id)}
				>
					<!-- Образец — маленький экран в этом дизайне: заголовок, две карточки и кнопка. -->
					<span class="look {s.id}" aria-hidden="true">
						<i class="t"></i>
						<i class="c c1" style:--subject="#4f7df5"><b></b><b class="short"></b></i>
						<i class="c c2" style:--subject="#1fa37a"><b></b></i>
						<i class="btn-mini"></i>
						{#if style === s.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{s.label}</span>
					<span class="about">{s.hint}</span>
				</button>
			{/each}
		</div>
	</div>

	<div>
		<p class="label" id="color-label">Цвет</p>
		<div class="colors" role="radiogroup" aria-labelledby="color-label">
			{#each PRESETS as p (p.id)}
				{@const on = !custom && accent.hue === p.hue}
				<button
					type="button"
					role="radio"
					aria-checked={on}
					aria-label={p.label}
					title={p.label}
					class="color"
					class:on
					class:ink={p.hue === null}
					style:--c={p.hue === null ? undefined : dot(p.hue)}
					onclick={() => pickPreset(p.hue)}
				>
					{#if on}<Check size={16} strokeWidth={3} />{/if}
				</button>
			{/each}
			<button
				type="button"
				role="radio"
				aria-checked={custom}
				aria-label="Свой цвет"
				title="Свой цвет"
				class="color rainbow"
				class:on={custom}
				onclick={pickCustom}
			>
				{#if custom}<Check size={16} strokeWidth={3} />{/if}
			</button>
		</div>
		{#if custom && accent.hue !== null}
			<label class="slider">
				<span class="row-label">Свой цвет</span>
				<input
					type="range"
					class="hue"
					min="0"
					max="359"
					value={accent.hue}
					aria-label="Оттенок"
					oninput={(e) => slide({ hue: Number(e.currentTarget.value) })}
				/>
			</label>
		{/if}
		<label class="slider" class:off={accent.hue === null}>
			<span class="row-label"
				>Насыщенность <span class="value num"
					>{accent.hue === null ? '' : `${accent.sat}% · ${satLabel}`}</span
				></span
			>
			<input
				type="range"
				class="sat"
				min="0"
				max="100"
				step="5"
				value={accent.sat}
				disabled={accent.hue === null}
				aria-label="Насыщенность"
				style:--from={accent.hue === null ? undefined : oklch(0.6, 0.02, accent.hue)}
				style:--to={accent.hue === null ? undefined : oklch(0.6, 0.24, accent.hue)}
				oninput={(e) => slide({ sat: Number(e.currentTarget.value) })}
			/>
		</label>
		<p class="hint">
			{accent.hue === null
				? '«Чернила» — чёрно-белая классика. Выберите цвет — и кнопки, фон и карточки станут в его тонах.'
				: 'Меньше — спокойнее для глаз, больше — цветнее кнопки, фон и карточки.'}
		</p>
	</div>

	<div>
		<p class="label">Картинка на фоне</p>
		<div class="bg-row">
			<span
				class="bgp"
				style:background-image={bgImage ? `url("${bgImage}")` : undefined}
				aria-hidden="true"
			>
				{#if !bgImage}<ImagePlus size={20} />{/if}
			</span>
			<div class="row wrap">
				<Button size="s" onclick={() => fileInput?.click()}
					><ImagePlus size={15} /> {bgImage ? 'Другая картинка' : 'Выбрать картинку'}</Button
				>
				{#if bgImage}
					<Button size="s" variant="ghost" onclick={dropBackground}
						><Trash2 size={15} /> Убрать</Button
					>
				{/if}
			</div>
		</div>
		<input
			bind:this={fileInput}
			class="sr-only"
			type="file"
			accept="image/*"
			tabindex="-1"
			aria-hidden="true"
			onchange={pickFile}
		/>
	</div>

	<div>
		<p class="label" id="icon-label">Значок</p>
		<div class="icons" role="radiogroup" aria-labelledby="icon-label">
			{#each ICONS as i (i.id)}
				<button
					type="button"
					role="radio"
					aria-checked={icon === i.id}
					aria-label={i.label}
					class="design icon"
					class:on={icon === i.id}
					onclick={() => pickIcon(i.id)}
				>
					<span class="ic" aria-hidden="true">
						<img src={iconSrc(i.id)} alt="" width="48" height="48" />
						{#if icon === i.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{i.label}</span>
				</button>
			{/each}
		</div>
		<p class="hint">
			Во вкладке браузера меняется сразу. На Android значок на экране обновится сам, на iPhone —
			если удалить сайт с экрана «Домой» и добавить снова.
		</p>
	</div>
	<p class="hint">Оформление сохраняется на этом устройстве.</p>
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: var(--s5);
	}
	.picker .label {
		margin-bottom: 10px;
		font-size: 14px;
		color: var(--text);
		font-weight: 620;
	}
	.seg {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 4px;
		padding: 4px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	.seg button {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		min-width: 0;
		height: 40px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 550;
		white-space: nowrap;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.seg button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.12);
	}
	/* На узком экране — без иконок: «Как в системе» должно поместиться целиком. */
	@media (max-width: 420px) {
		.seg button {
			font-size: 13px;
		}
		.seg button :global(svg) {
			display: none;
		}
	}

	/* ---------- Дизайны ---------- */
	.designs {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
		gap: var(--s3);
	}
	.design {
		display: flex;
		flex-direction: column;
		align-items: stretch;
		gap: 4px;
		padding: 8px 8px 10px;
		border: 1px solid transparent;
		border-radius: 18px;
		background: transparent;
		color: var(--text);
		font: inherit;
		text-align: left;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	.design:hover {
		background: var(--surface-2);
	}
	.design:active {
		transform: scale(0.98);
	}
	.design.on {
		border-color: var(--accent);
		background: color-mix(in srgb, var(--accent) 6%, transparent);
	}
	.design .name {
		margin-top: 6px;
		padding: 0 4px;
		font-size: 14px;
		font-weight: 620;
	}
	.design .about {
		padding: 0 4px;
		color: var(--text-2);
		font-size: 12.5px;
		line-height: 1.35;
	}
	.tick {
		position: absolute;
		top: 7px;
		right: 7px;
		display: grid;
		place-items: center;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		background: var(--accent);
		color: var(--accent-text);
		box-shadow: 0 2px 6px rgb(0 0 0 / 0.25);
	}
	/* Мини-экран дизайна: при наведении карточки всплывают по очереди. */
	.look {
		--m-bg: var(--bg);
		--m-card: var(--surface);
		--m-text: var(--text);
		--m-line: var(--text-3);
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 6px;
		height: 96px;
		padding: 11px 12px;
		border-radius: 14px;
		overflow: hidden;
		background: var(--m-bg);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.22);
	}
	.t {
		display: block;
		width: 42%;
		height: 7px;
		border-radius: 4px;
		background: var(--m-text);
		opacity: 0.85;
	}
	.c {
		position: relative;
		display: flex;
		flex-direction: column;
		justify-content: center;
		gap: 3px;
		height: 22px;
		padding-left: 10px;
		border-radius: 7px;
		background: var(--m-card);
		transition: transform 260ms cubic-bezier(0.3, 1.4, 0.5, 1);
	}
	.c::before {
		content: '';
		position: absolute;
		left: 4px;
		top: 5px;
		bottom: 5px;
		width: 2px;
		border-radius: 1px;
		background: var(--subject);
	}
	.c b {
		display: block;
		width: 58%;
		height: 3px;
		border-radius: 2px;
		background: var(--m-line);
		opacity: 0.7;
	}
	.c b.short {
		width: 34%;
		opacity: 0.45;
	}
	.c2 {
		width: 76%;
	}
	.btn-mini {
		position: absolute;
		right: 11px;
		bottom: 10px;
		width: 28px;
		height: 12px;
		border-radius: 6px;
		background: var(--accent);
	}
	.design:hover .c1 {
		transform: translateY(-2px);
	}
	.design:hover .c2 {
		transform: translateY(-2px);
		transition-delay: 60ms;
	}
	.look.plain .c {
		box-shadow: 0 0 0 1px rgb(127 127 127 / 0.14);
	}
	.look.glass {
		background:
			radial-gradient(
				70% 70% at 10% 15%,
				color-mix(in srgb, var(--mesh-1) 60%, transparent),
				transparent
			),
			radial-gradient(
				60% 70% at 95% 95%,
				color-mix(in srgb, var(--mesh-3) 55%, transparent),
				transparent
			),
			radial-gradient(
				55% 60% at 85% 10%,
				color-mix(in srgb, var(--mesh-2) 50%, transparent),
				transparent
			),
			var(--m-bg);
	}
	.look.glass .c {
		background: color-mix(in srgb, var(--m-card) 52%, transparent);
		box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.45);
		backdrop-filter: blur(6px);
	}
	.look.depth {
		background:
			radial-gradient(
				120% 60% at 50% -20%,
				color-mix(in srgb, var(--accent) 14%, transparent),
				transparent
			),
			var(--m-bg);
	}
	.look.depth .c {
		background-image: linear-gradient(180deg, rgb(255 255 255 / 0.55), transparent);
		box-shadow:
			inset 0 1px 0 rgb(255 255 255 / 0.7),
			0 6px 12px -6px rgb(0 0 0 / 0.5);
	}
	.look.depth .btn-mini {
		background-image: linear-gradient(180deg, rgb(255 255 255 / 0.3), transparent);
		box-shadow: 0 4px 8px -3px color-mix(in srgb, var(--accent) 70%, transparent);
	}
	.look.neon .c {
		border: 1px solid transparent;
		background:
			linear-gradient(var(--m-card), var(--m-card)) padding-box,
			linear-gradient(135deg, var(--subject), var(--mesh-2), var(--mesh-3)) border-box;
		box-shadow: 0 0 12px -3px var(--subject);
	}
	.look.neon .btn-mini {
		box-shadow: 0 0 10px -1px var(--accent);
	}
	.look.paper {
		--m-bg: #f3eee3;
		--m-card: #fffdf7;
		--m-text: #2a2317;
		--m-line: #7c7159;
	}
	.look.paper .t {
		height: 8px;
		border-radius: 1px;
	}
	.look.paper .c {
		box-shadow: 0 4px 8px -6px rgb(90 64 20 / 0.6);
	}
	.look.paper .btn-mini {
		background: #3a2e1d;
	}

	/* ---------- Цвет ---------- */
	.colors {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}
	.color {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		padding: 0;
		border: 0;
		border-radius: 50%;
		background: var(--c);
		color: #fff;
		box-shadow:
			inset 0 0 0 1px rgb(0 0 0 / 0.08),
			0 0 0 0 transparent;
		transition:
			transform 140ms var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.color:hover {
		transform: scale(1.08);
	}
	.color.on {
		box-shadow:
			0 0 0 3px var(--surface),
			0 0 0 5px var(--c, var(--text));
	}
	.color.ink {
		background: linear-gradient(135deg, #0d0d0f 50%, #ffffff 50%);
		color: #fff;
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.4);
	}
	.color.ink.on {
		box-shadow:
			inset 0 0 0 1px rgb(127 127 127 / 0.4),
			0 0 0 3px var(--surface),
			0 0 0 5px var(--text);
	}
	.color.ink :global(svg) {
		filter: drop-shadow(0 0 2px rgb(0 0 0 / 0.8));
	}
	.color.rainbow {
		--c: var(--text);
		background: conic-gradient(
			from 0deg,
			#ff5e5e,
			#ffb03a,
			#f5e663,
			#5fd068,
			#35c7d6,
			#4f7df5,
			#a35cf0,
			#ff5eb3,
			#ff5e5e
		);
	}
	.color.rainbow :global(svg) {
		filter: drop-shadow(0 0 2px rgb(0 0 0 / 0.6));
	}
	.slider {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-top: var(--s4);
	}
	.slider.off {
		opacity: 0.55;
	}
	.row-label {
		display: flex;
		justify-content: space-between;
		gap: 8px;
		font-size: 14px;
		font-weight: 550;
	}
	.value {
		color: var(--text-2);
		font-weight: 500;
	}
	input[type='range'] {
		width: 100%;
		height: 28px;
		margin: 0;
		background: transparent;
		appearance: none;
		-webkit-appearance: none;
		cursor: pointer;
	}
	input[type='range']:disabled {
		cursor: default;
	}
	input[type='range']::-webkit-slider-runnable-track {
		height: 12px;
		border-radius: 6px;
		background: var(--track);
		box-shadow: inset 0 0 0 1px rgb(0 0 0 / 0.08);
	}
	input[type='range']::-moz-range-track {
		height: 12px;
		border-radius: 6px;
		background: var(--track);
	}
	input[type='range']::-webkit-slider-thumb {
		-webkit-appearance: none;
		width: 24px;
		height: 24px;
		margin-top: -6px;
		border-radius: 50%;
		background: #fff;
		box-shadow:
			0 0 0 1px rgb(0 0 0 / 0.12),
			0 2px 6px rgb(0 0 0 / 0.3);
	}
	input[type='range']::-moz-range-thumb {
		width: 24px;
		height: 24px;
		border: 0;
		border-radius: 50%;
		background: #fff;
		box-shadow:
			0 0 0 1px rgb(0 0 0 / 0.12),
			0 2px 6px rgb(0 0 0 / 0.3);
	}
	.hue {
		--track: linear-gradient(
			90deg,
			#ff5e5e,
			#ffb03a,
			#f5e663,
			#5fd068,
			#35c7d6,
			#4f7df5,
			#a35cf0,
			#ff5eb3,
			#ff5e5e
		);
	}
	.sat {
		--track: linear-gradient(90deg, var(--from, var(--surface-3)), var(--to, var(--surface-3)));
	}

	/* ---------- Картинка и значок ---------- */
	.bg-row {
		display: flex;
		align-items: center;
		gap: var(--s3);
	}
	.bgp {
		flex: none;
		display: grid;
		place-items: center;
		width: 96px;
		height: 60px;
		border-radius: 12px;
		background: var(--surface-2) center / cover;
		color: var(--text-3);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
	}
	.icons {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
		gap: var(--s2);
	}
	.design.icon {
		align-items: center;
		text-align: center;
	}
	.design.icon .name {
		margin-top: 2px;
		font-size: 13px;
		font-weight: 550;
	}
	.ic {
		position: relative;
		display: grid;
		place-items: center;
		width: 100%;
		height: 64px;
	}
	.ic img {
		width: 48px;
		height: 48px;
		border-radius: 11px;
		box-shadow: 0 6px 14px -8px rgb(0 0 0 / 0.5);
		transition: transform 220ms cubic-bezier(0.3, 1.5, 0.5, 1);
	}
	.design:hover .ic img {
		transform: translateY(-2px) rotate(-3deg);
	}
	.design.on .ic img {
		transform: scale(1.06);
	}
	@media (prefers-reduced-motion: reduce) {
		.c,
		.ic img {
			transition: none;
		}
	}
</style>
