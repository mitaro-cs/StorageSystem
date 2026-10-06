<script lang="ts">
	// Галочка «Принимаю правила» в формах регистрации: без неё форма не отправится.
	let { checked = $bindable(false) }: { checked?: boolean } = $props();
</script>

<label class="agree small">
	<input type="checkbox" bind:checked required />
	<span
		>Принимаю <a href="/terms" target="_blank" rel="noopener"
			>правила сайта и условия хранения данных</a
		></span
	>
</label>

<style>
	.agree {
		display: flex;
		align-items: flex-start;
		gap: 8px;
		color: var(--text-2);
		cursor: pointer;
	}
	.agree input {
		flex: none;
		width: 18px;
		height: 18px;
		margin-top: 1px;
	}
</style>
