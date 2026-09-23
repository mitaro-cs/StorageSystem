<script lang="ts">
	let { value = $bindable() }: { value: string } = $props();
</script>

<div>
	<label class="label" for="fio">ФИО</label>
	<input
		id="fio"
		class="input"
		bind:value
		autocomplete="name"
		autocapitalize="words"
		maxlength="64"
		placeholder="Иванов Иван Иванович"
		required
	/>
	<p class="hint">Фамилия, имя и отчество (если есть). Так вас увидят одногруппники.</p>
</div>
