<script lang="ts">
	import { onMount } from 'svelte';
	import { Check, ImagePlus, Monitor, Moon, Sun } from '@lucide/svelte';
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
	import {
		BACKGROUNDS,
		ICONS,
		currentBackground,
		currentIcon,
		customBackground,
		setBackground,
		setCustomBackground,
		setIcon,
		type Background
	} from '$lib/looks';
	import { iconSrc, type AppIcon } from '$lib/appIcon.svelte';
	import { toast } from '$lib/toasts.svelte';

	// Оформление под себя: режим (светлый, тёмный, как в системе), цвет и стиль карточек. Меняется
	// сразу, хранится на этом устройстве.
	let theme = $state<Theme>('system');
	let palette = $state<Palette>('classic');
	let style = $state<Style>('plain');
	let bg = $state<Background>('none');
	let bgImage = $state<string | null>(null);
	let icon = $state<AppIcon>('light');
	let fileInput: HTMLInputElement | undefined = $state();
	onMount(() => {
		theme = currentTheme();
		palette = currentPalette();
		style = currentStyle();
		bg = currentBackground();
		bgImage = customBackground();
		icon = currentIcon();
	});

	function pickBackground(b: Background) {
		if (b === 'custom' && !bgImage) {
			fileInput?.click();
			return;
		}
		bg = b;
		setBackground(b);
	}

	async function pickFile(e: Event) {
		const file = (e.currentTarget as HTMLInputElement).files?.[0];
		(e.currentTarget as HTMLInputElement).value = '';
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
		bg = 'custom';
	}

	function pickIcon(i: AppIcon) {
		icon = i;
		setIcon(i);
	}

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
					<!-- Образец — маленький экран: заголовок, две карточки с полосой предмета и кнопка. -->
					<span class="look {s.id}" aria-hidden="true">
						<i class="t"></i>
						<i class="c c1" style:--subject="#4f7df5"><b></b></i>
						<i class="c c2" style:--subject="#1fa37a"><b></b></i>
						<i class="btn-mini"></i>
						{#if style === s.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{s.label}</span>
				</button>
			{/each}
		</div>
		<p class="hint">{STYLES.find((s) => s.id === style)?.hint}</p>
	</div>
	<div>
		<p class="label" id="bg-label">Фон</p>
		<div class="bgs" role="radiogroup" aria-labelledby="bg-label">
			{#each BACKGROUNDS as b (b.id)}
				<button
					type="button"
					role="radio"
					aria-checked={bg === b.id}
					aria-label={b.label}
					class="swatch"
					class:on={bg === b.id}
					onclick={() => pickBackground(b.id)}
				>
					<span
						class="bgp {b.id}"
						style:--img={b.id === 'custom' && bgImage ? `url("${bgImage}")` : undefined}
						aria-hidden="true"
					>
						{#if b.id === 'custom' && !bgImage}<ImagePlus size={20} />{/if}
						{#if bg === b.id}<span class="tick"><Check size={13} strokeWidth={3} /></span>{/if}
					</span>
					<span class="name">{b.label}</span>
				</button>
			{/each}
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
		{#if bg === 'custom' && bgImage}
			<button type="button" class="linklike small" onclick={() => fileInput?.click()}
				>Выбрать другую картинку</button
			>
		{/if}
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
					class="swatch"
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
		grid-template-columns: repeat(auto-fill, minmax(122px, 1fr));
		gap: var(--s2);
	}
	.styles + .hint {
		margin-top: 6px;
	}
	/* Мини-экран стиля: при наведении карточки всплывают по очереди, выбранный — со свечением. */
	.look {
		--surface-m: var(--surface);
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 5px;
		width: 100%;
		height: 78px;
		padding: 9px 10px;
		border-radius: 14px;
		overflow: hidden;
		background: var(--bg);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
		transition: box-shadow 220ms var(--ease);
	}
	.swatch.on .look {
		box-shadow:
			inset 0 0 0 1px rgb(127 127 127 / 0.25),
			0 0 0 3px color-mix(in srgb, var(--accent) 22%, transparent);
	}
	.t {
		display: block;
		width: 38%;
		height: 6px;
		border-radius: 3px;
		background: var(--text);
		opacity: 0.8;
	}
	.c {
		position: relative;
		display: flex;
		align-items: center;
		height: 17px;
		padding-left: 8px;
		border-radius: 6px;
		background: var(--surface-m);
		transition: transform 260ms cubic-bezier(0.3, 1.4, 0.5, 1);
	}
	.c::before {
		content: '';
		position: absolute;
		left: 3px;
		top: 4px;
		bottom: 4px;
		width: 2px;
		border-radius: 1px;
		background: var(--subject);
	}
	.c b {
		display: block;
		width: 55%;
		height: 3px;
		border-radius: 2px;
		background: var(--text-3);
		opacity: 0.6;
	}
	.c2 {
		width: 78%;
	}
	.btn-mini {
		position: absolute;
		right: 9px;
		bottom: 8px;
		width: 22px;
		height: 10px;
		border-radius: 5px;
		background: var(--accent);
	}
	.swatch:hover .c1 {
		transform: translateY(-2px);
	}
	.swatch:hover .c2 {
		transform: translateY(-2px);
		transition-delay: 60ms;
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
	.look.outline .c {
		background: transparent;
		box-shadow: inset 0 0 0 1.2px var(--border-strong);
	}
	.look.neon .c {
		border: 1px solid transparent;
		background:
			linear-gradient(var(--surface), var(--surface)) padding-box,
			linear-gradient(135deg, var(--subject), #ff4fd8, #33d6ff) border-box;
		box-shadow: 0 0 10px -3px var(--subject);
	}
	.look.paper {
		background: #f3eee3;
	}
	.look.paper .t {
		height: 7px;
		border-radius: 1px;
		background: #3d3120;
	}
	.look.paper .c {
		background: #fffdf7;
		box-shadow: 0 4px 8px -6px rgb(90 64 20 / 0.6);
	}
	.look.comic .c {
		box-shadow:
			inset 0 0 0 1.5px var(--text),
			2px 2px 0 var(--text);
	}
	.look.comic .btn-mini {
		box-shadow: 1.5px 1.5px 0 var(--text);
	}
	/* Фон: образец узора или картинки. */
	.bgs,
	.icons {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
		gap: var(--s2);
	}
	.bgp {
		position: relative;
		display: grid;
		place-items: center;
		width: 100%;
		height: 56px;
		border-radius: 12px;
		overflow: hidden;
		background: var(--bg);
		color: var(--text-3);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
	}
	.bgp.aurora {
		background:
			radial-gradient(70% 70% at 10% 10%, rgb(124 92 255 / 0.55), transparent 70%),
			radial-gradient(70% 70% at 95% 30%, rgb(56 189 170 / 0.5), transparent 70%),
			radial-gradient(80% 70% at 50% 110%, rgb(59 130 246 / 0.45), transparent 72%), var(--bg);
	}
	.bgp.sunset {
		background:
			radial-gradient(80% 70% at 0% 0%, rgb(255 159 67 / 0.6), transparent 70%),
			radial-gradient(70% 70% at 100% 30%, rgb(238 77 126 / 0.5), transparent 70%),
			radial-gradient(80% 70% at 40% 110%, rgb(168 107 255 / 0.45), transparent 72%), var(--bg);
	}
	.bgp.ocean {
		background:
			radial-gradient(80% 70% at 100% 0%, rgb(47 128 255 / 0.55), transparent 70%),
			radial-gradient(70% 70% at 0% 60%, rgb(34 211 238 / 0.45), transparent 70%),
			radial-gradient(80% 70% at 70% 110%, rgb(18 56 168 / 0.5), transparent 72%), var(--bg);
	}
	.bgp.mint {
		background:
			radial-gradient(80% 70% at 0% 0%, rgb(47 182 124 / 0.55), transparent 70%),
			radial-gradient(70% 70% at 100% 45%, rgb(163 230 53 / 0.4), transparent 70%),
			radial-gradient(80% 70% at 30% 110%, rgb(20 184 166 / 0.45), transparent 72%), var(--bg);
	}
	.bgp.grid {
		background:
			linear-gradient(color-mix(in srgb, var(--text) 12%, transparent) 1px, transparent 1px) 0 0 /
				10px 10px,
			linear-gradient(90deg, color-mix(in srgb, var(--text) 12%, transparent) 1px, transparent 1px)
				0 0 / 10px 10px,
			var(--bg);
	}
	.bgp.dots {
		background:
			radial-gradient(
					circle,
					color-mix(in srgb, var(--text) 30%, transparent) 1px,
					transparent 1.4px
				)
				0 0 / 9px 9px,
			var(--bg);
	}
	.bgp.lines {
		background:
			linear-gradient(90deg, transparent 14px, rgb(238 77 126 / 0.45) 14px 15px, transparent 15px),
			linear-gradient(color-mix(in srgb, #2f80ff 30%, transparent) 1px, transparent 1px) 0 0 / 100%
				11px,
			var(--bg);
	}
	.bgp.custom {
		background: var(--img, var(--surface-2)) center / cover;
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
	.swatch:hover .ic img {
		transform: translateY(-2px) rotate(-3deg);
	}
	.swatch.on .ic img {
		transform: scale(1.06);
	}
	@media (prefers-reduced-motion: reduce) {
		.c,
		.ic img {
			transition: none;
		}
	}
</style>
