package root.repositories

import org.springframework.data.repository.CrudRepository
import root.data.entity.tasks.surveus.Option

interface OptionRepository : CrudRepository<Option, Long>
