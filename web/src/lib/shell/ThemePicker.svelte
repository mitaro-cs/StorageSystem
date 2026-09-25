<script lang="ts">
	import { onMount } from 'svelte';
	import { Check, Monitor, Moon, Sun } from '@lucide/svelte';
	import {
		PALETTES,
		STYLES,
		currentPalette,
		currentStyle,
		currentTheme,
		setPalette,
		setStyle,
		setTheme,
		type Palette,
		type Style,
		type Theme
	} from '$lib/theme';

	// Оформление под себя: режим (светлый, тёмный, как в системе), цвет и стиль карточек. Меняется
	// сразу, хранится на этом устройстве.
	let theme = $state<Theme>('system');
	let palette = $state<Palette>('classic');
	let style = $state<Style>('plain');
	onMount(() => {
		theme = currentTheme();
		palette = currentPalette();
		style = currentStyle();
	});

	function pickStyle(s: Style) {
		style = s;
		setStyle(s);
	}

	const modes: { value: Theme; label: string; icon: typeof Sun }[] = [
		{ value: 'system', label: 'Как в системе', icon: Monitor },
		{ value: 'light', label: 'Светлая', icon: Sun },
		{ value: 'dark', label: 'Тёмная', icon: Moon }
	];

	function pickTheme(t: Theme) {
		theme = t;
		setTheme(t);
	}

	function pickPalette(p: Palette) {
		palette = p;
		setPalette(p);
	}
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
		<p class="label" id="palette-label">Цвет</p>
		<div class="swatches" role="radiogroup" aria-labelledby="palette-label">
			{#each PALETTES as p (p.id)}
				<button
					type="button"
					role="radio"
					aria-checked={palette === p.id}
					aria-label={p.label}
					class="swatch"
					class:on={palette === p.id}
					onclick={() => pickPalette(p.id)}
				>
					<!-- Образец: слева светлый режим, справа тёмный — фон, «кнопка» и строка текста. -->
					<span class="mini" aria-hidden="true">
						<span class="half" style:--bg={p.light[0]} style:--acc={p.light[1]}>
							<i class="pill"></i><i class="line"></i>
						</span>
						<span class="half" style:--bg={p.dark[0]} style:--acc={p.dark[1]}>
							<i class="pill"></i><i class="line"></i>
						</span>
						{#if palette === p.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{p.label}</span>
				</button>
			{/each}
		</div>
	</div>
	<div>
		<p class="label" id="style-label">Стиль</p>
		<div class="styles" role="radiogroup" aria-labelledby="style-label">
			{#each STYLES as s (s.id)}
				<button
					type="button"
					role="radio"
					aria-checked={style === s.id}
					aria-label={s.label}
					title={s.hint}
					class="swatch"
					class:on={style === s.id}
					onclick={() => pickStyle(s.id)}
				>
					<!-- Образец: фон страницы и две карточки в этом стиле. -->
					<span class="look {s.id}" aria-hidden="true">
						<i class="c c1"></i><i class="c c2"></i>
						{#if style === s.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{s.label}</span>
				</button>
			{/each}
		</div>
		<p class="hint">{STYLES.find((s) => s.id === style)?.hint}</p>
	</div>
	<p class="hint">Оформление сохраняется на этом устройстве.</p>
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
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
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
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
	.swatches {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
		gap: var(--s2);
	}
	.swatch {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		padding: 6px 6px 8px;
		border: 1px solid transparent;
		border-radius: var(--r);
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 13px;
		font-weight: 550;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	.swatch:hover {
		background: var(--surface-2);
	}
	.swatch:active {
		transform: scale(0.97);
	}
	.swatch.on {
		border-color: var(--accent);
		color: var(--text);
	}
	.mini {
		position: relative;
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		width: 100%;
		height: 52px;
		border-radius: 12px;
		overflow: hidden;
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
	}
	.half {
		display: flex;
		flex-direction: column;
		justify-content: center;
		align-items: flex-start;
		gap: 5px;
		min-width: 0;
		padding: 0 7px;
		overflow: hidden;
		background: var(--bg);
	}
	.pill,
	.line {
		display: block;
		flex: none;
		max-width: 100%;
		background: var(--acc);
	}
	.pill {
		width: 28px;
		height: 11px;
		border-radius: 6px;
	}
	.line {
		width: 36px;
		height: 4px;
		border-radius: 2px;
		opacity: 0.35;
	}
	.tick {
		position: absolute;
		top: 5px;
		right: 5px;
		display: grid;
		place-items: center;
		width: 20px;
		height: 20px;
		border-radius: 50%;
		background: var(--accent);
		color: var(--accent-text);
	}
	.name {
		white-space: nowrap;
	}
	.styles {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
		gap: var(--s2);
	}
	.styles + .hint {
		margin-top: 6px;
	}
	.look {
		position: relative;
		display: flex;
		flex-direction: column;
		justify-content: center;
		gap: 6px;
		width: 100%;
		height: 60px;
		padding: 0 12px;
		border-radius: 12px;
		overflow: hidden;
		background: var(--bg);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
	}
	.c {
		display: block;
		height: 16px;
		border-radius: 6px;
		background: var(--surface);
	}
	.c2 {
		width: 70%;
	}
	.look.depth .c {
		background-image: linear-gradient(180deg, rgb(255 255 255 / 0.5), transparent);
		box-shadow:
			inset 0 1px 0 rgb(255 255 255 / 0.6),
			0 6px 10px -6px rgb(0 0 0 / 0.45);
	}
	.look.glass {
		background:
			radial-gradient(60% 70% at 15% 20%, rgb(124 92 255 / 0.45), transparent),
			radial-gradient(55% 70% at 90% 90%, rgb(255 146 64 / 0.45), transparent),
			radial-gradient(50% 60% at 80% 10%, rgb(56 189 170 / 0.4), transparent), var(--bg);
	}
	.look.glass .c {
		background: color-mix(in srgb, var(--surface) 55%, transparent);
		box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.4);
		backdrop-filter: blur(6px);
	}
	.look.tint .c1 {
		background: color-mix(in srgb, #4f7df5 22%, var(--surface));
	}
	.look.tint .c2 {
		background: color-mix(in srgb, #1fa37a 22%, var(--surface));
	}
</style>
