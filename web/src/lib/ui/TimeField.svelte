<script lang="ts">
	import { Clock } from '@lucide/svelte';
	import { tick } from 'svelte';
	import FieldPopup from './FieldPopup.svelte';
	import { maskTime, parseTime } from './dates';

	// Время в стиле сайта (0.9.8): напечатать «0930» или выбрать час и минуты. Значение – 'HH:MM'.
	let {
		value = $bindable(''),
		id,
		label,
		required = false,
		onchange
	}: {
		value: string;
		id?: string;
		label?: string;
		required?: boolean;
		onchange?: (v: string) => void;
	} = $props();

	let text = $state(value);
	let typing = $state(false);
	let open = $state(false);
	let box: HTMLDivElement | undefined = $state();
	let list: HTMLDivElement | undefined = $state();

	$effect(() => {
		const v = value;
		if (!typing) text = v;
	});

	const hours = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
	const minutes = $derived.by(() => {
		const base = Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0'));
		const own = value.slice(3, 5);
		return own && !base.includes(own) ? [...base, own].sort() : base;
	});
	const hh = $derived(value.slice(0, 2));
	const mm = $derived(value.slice(3, 5));

	function set(v: string) {
		value = v;
		text = v;
		onchange?.(v);
	}

	function input(e: Event) {
		typing = true;
		const el = e.currentTarget as HTMLInputElement;
		text = maskTime(el.value);
		el.value = text;
		const t = parseTime(text);
		if (t && t.length === 5 && text.length === 5 && t !== value) set(t);
	}

	function blur() {
		typing = false;
		const t = parseTime(text);
		if (t) set(t);
		else if (!text.trim()) {
			if (value) set('');
		} else text = value;
	}

	async function toggle() {
		open = !open;
		if (!open) return;
		await tick();
		await tick();
		// Выбранные час и минуты – по центру колонок.
		for (const el of list?.querySelectorAll<HTMLElement>('[aria-pressed="true"]') ?? [])
			el.scrollIntoView({ block: 'center' });
	}
</script>

<div class="time" bind:this={box}>
	<input
		{id}
		class="input num"
		type="text"
		inputmode="numeric"
		autocomplete="off"
		placeholder="чч:мм"
		aria-label={label}
		{required}
		value={text}
		oninput={input}
		onblur={blur}
	/>
	<button
		type="button"
		class="clock"
		aria-label="Выбрать время"
		aria-expanded={open}
		onclick={toggle}><Clock size={18} /></button
	>
</div>

<FieldPopup bind:open anchor={box} label="Время">
	<div class="cols" bind:this={list}>
		<div class="col" role="group" aria-label="Часы">
			{#each hours as h (h)}
				<button
					type="button"
					class="num"
					aria-pressed={h === hh}
					onclick={() => set(`${h}:${mm || '00'}`)}>{h}</button
				>
			{/each}
		</div>
		<div class="col" role="group" aria-label="Минуты">
			{#each minutes as m (m)}
				<button
					type="button"
					class="num"
					aria-pressed={m === mm}
					onclick={() => {
						set(`${hh || '09'}:${m}`);
						open = false;
					}}>{m}</button
				>
			{/each}
		</div>
	</div>
</FieldPopup>

<style>
	.time {
		position: relative;
	}
	.time .input {
		padding-right: 48px;
	}
	.clock {
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
	.clock:hover,
	.clock[aria-expanded='true'] {
		background: var(--surface-2);
		color: var(--text);
	}
	.cols {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 6px;
		width: 160px;
	}
	.col {
		display: flex;
		flex-direction: column;
		gap: 2px;
		max-height: 232px;
		overflow-y: auto;
		scroll-snap-type: y proximity;
	}
	.col button {
		flex: none;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text);
		font: inherit;
		font-size: 15px;
		scroll-snap-align: center;
	}
	.col button:hover {
		background: var(--surface-2);
	}
	.col button[aria-pressed='true'] {
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 650;
	}
</style>
