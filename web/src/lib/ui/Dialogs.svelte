<script lang="ts">
	import { tick } from 'svelte';
	import Button from './Button.svelte';
	import Modal from './Modal.svelte';
	import { answer, dialog } from './ask.svelte';

	// Окно для ask() и askText() (ask.svelte.ts): одно на всё приложение, в корневом макете.
	let value = $state('');
	let form: HTMLFormElement | undefined = $state();
	const current = $derived(dialog.current);

	$effect(() => {
		if (!current) return;
		value = current.kind === 'text' ? (current.options.value ?? '') : '';
		// Фокус — сразу в поле или на кнопку согласия: Enter отвечает, Esc отменяет.
		tick().then(() => {
			const box = form?.closest('dialog');
			const target =
				box?.querySelector<HTMLElement>('#ask-input') ??
				box?.querySelector<HTMLElement>('footer button:last-of-type');
			target?.focus({ preventScroll: true });
		});
	});

	function cancel() {
		answer(current?.kind === 'confirm' ? false : null);
	}
</script>

{#if current}
	<Modal open={true} title={current.options.title ?? 'Подтвердите'} onclose={cancel}>
		<form
			id="ask-form"
			class="stack"
			bind:this={form}
			onsubmit={(e) => {
				e.preventDefault();
				answer(current.kind === 'confirm' ? true : value);
			}}
		>
			<p class="message">{current.message}</p>
			{#if current.kind === 'text'}
				<div>
					{#if current.options.label}<label class="label" for="ask-input"
							>{current.options.label}</label
						>{/if}
					<input
						id="ask-input"
						class="input"
						class:num={current.options.inputmode === 'numeric'}
						bind:value
						placeholder={current.options.placeholder}
						inputmode={current.options.inputmode}
						autocomplete={current.options.autocomplete ?? 'off'}
						maxlength={current.options.maxlength}
						aria-label={current.options.label ? undefined : current.message}
						required
					/>
				</div>
			{/if}
		</form>
		{#snippet footer()}
			<Button onclick={cancel}>Отмена</Button>
			<Button variant={current?.options.danger ? 'danger' : 'primary'} type="submit" form="ask-form"
				>{current?.options.ok ?? 'Готово'}</Button
			>
		{/snippet}
	</Modal>
{/if}

<style>
	.message {
		margin: 0;
		white-space: pre-line;
	}
</style>
