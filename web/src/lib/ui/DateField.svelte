<script lang="ts">
	import { CalendarDays, ChevronLeft, ChevronRight } from '@lucide/svelte';
	import FieldPopup from './FieldPopup.svelte';
	import { isoDay, maskDay, monthGrid, monthTitle, parseDay, showDay } from './dates';

	// Дата в стиле сайта (0.9.8): печатать «12.10.2026» или выбрать в своём календаре – вместо
	// системного поля, которое на каждом устройстве выглядело по-своему. Значение – 'YYYY-MM-DD'.
	let {
		value = $bindable(''),
		id,
		label,
		min,
		required = false,
		onchange
	}: {
		value: string;
		id?: string;
		label?: string;
		min?: string;
		required?: boolean;
		onchange?: (v: string) => void;
	} = $props();

	let text = $state(showDay(value));
	let typing = $state(false);
	let open = $state(false);
	let box: HTMLDivElement | undefined = $state();
	const today = new Date();
	const todayIso = isoDay(today.getFullYear(), today.getMonth(), today.getDate());
	let year = $state(today.getFullYear());
	let month = $state(today.getMonth());

	// Значение поменяли снаружи – показываем его, если человек не печатает прямо сейчас.
	$effect(() => {
		const v = value;
		if (!typing) text = showDay(v);
	});

	const invalid = $derived(
		(!!text && !typing && !parseDay(text)) || (!!value && !!min && value < min)
	);

	function set(v: string) {
		value = v;
		text = showDay(v);
		onchange?.(v);
	}

	function input(e: Event) {
		typing = true;
		const el = e.currentTarget as HTMLInputElement;
		text = maskDay(el.value);
		el.value = text;
		const iso = parseDay(text);
		if (iso && iso !== value) set(iso);
	}

	function blur() {
		typing = false;
		const iso = parseDay(text);
		if (iso) set(iso);
		else if (!text.trim()) {
			if (value) set('');
		} else text = showDay(value);
	}

	function toggle() {
		if (!open) {
			const base = value || (min && min > todayIso ? min : todayIso);
			year = Number(base.slice(0, 4));
			month = Number(base.slice(5, 7)) - 1;
		}
		open = !open;
	}

	function shift(by: number) {
		const d = new Date(year, month + by, 1);
		year = d.getFullYear();
		month = d.getMonth();
	}

	function pick(day: number) {
		set(isoDay(year, month, day));
		open = false;
		box?.querySelector('input')?.focus();
	}
</script>

<div class="date" bind:this={box}>
	<input
		{id}
		class="input num"
		type="text"
		inputmode="numeric"
		autocomplete="off"
		placeholder="дд.мм.гггг"
		aria-label={label}
		aria-invalid={invalid || undefined}
		{required}
		value={text}
		oninput={input}
		onblur={blur}
	/>
	<button
		type="button"
		class="cal"
		aria-label="Выбрать дату в календаре"
		aria-expanded={open}
		onclick={toggle}><CalendarDays size={18} /></button
	>
</div>

<FieldPopup bind:open anchor={box} label="Календарь">
	<div class="month">
		<div class="head">
			<button type="button" class="nav" aria-label="Предыдущий месяц" onclick={() => shift(-1)}
				><ChevronLeft size={18} /></button
			>
			<strong>{monthTitle(year, month)}</strong>
			<button type="button" class="nav" aria-label="Следующий месяц" onclick={() => shift(1)}
				><ChevronRight size={18} /></button
			>
		</div>
		<div class="grid wd" aria-hidden="true">
			{#each ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'] as d (d)}<span>{d}</span>{/each}
		</div>
		{#each monthGrid(year, month) as week, w (w)}
			<div class="grid">
				{#each week as d, i (i)}
					{#if d}
						{@const iso = isoDay(year, month, d)}
						<button
							type="button"
							class="day num"
							class:today={iso === todayIso}
							class:on={iso === value}
							disabled={!!min && iso < min}
							aria-pressed={iso === value}
							aria-label={showDay(iso)}
							onclick={() => pick(d)}>{d}</button
						>
					{:else}<span></span>{/if}
				{/each}
			</div>
		{/each}
		<div class="foot">
			<button type="button" class="linklike" onclick={() => (set(todayIso), (open = false))}
				>Сегодня</button
			>
			{#if !required && value}
				<button type="button" class="linklike" onclick={() => (set(''), (open = false))}
					>Очистить</button
				>
			{/if}
		</div>
	</div>
</FieldPopup>

<style>
	.date {
		position: relative;
	}
	.date .input {
		padding-right: 48px;
	}
	.cal {
		position: absolute;
		top: 50%;
		right: 6px;
		translate: 0 -50%;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
	}
	.cal:hover,
	.cal[aria-expanded='true'] {
		background: var(--surface-2);
		color: var(--text);
	}
	.month {
		width: 284px;
		padding: 4px;
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 6px;
	}
	.head strong {
		font-size: 15px;
	}
	.nav {
		display: grid;
		place-items: center;
		width: 34px;
		height: 34px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--text-2);
	}
	.nav:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(7, 1fr);
		gap: 2px;
	}
	.wd span {
		padding: 4px 0;
		color: var(--text-3);
		font-size: 12px;
		font-weight: 600;
		text-align: center;
	}
	.day {
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text);
		font: inherit;
		font-size: 14px;
	}
	.day:hover:not(:disabled) {
		background: var(--surface-2);
	}
	.day.today {
		box-shadow: inset 0 0 0 1.5px var(--border-strong);
		font-weight: 650;
	}
	.day.on {
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 650;
	}
	.day:disabled {
		color: var(--text-3);
		opacity: 0.45;
	}
	.foot {
		display: flex;
		justify-content: space-between;
		margin-top: 8px;
		padding: 4px 6px 0;
		border-top: 1px solid var(--border);
	}
	.foot .linklike {
		padding: 6px 0;
		font-size: 14px;
	}
</style>
