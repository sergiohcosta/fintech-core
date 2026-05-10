export interface CategoryModel {
  id: string;
  name: string;
  icon: string;
  color: string;
  parentId?: string;
  children: CategoryModel[];
}

export interface CategoryCreate {
  name: string;
  icon: string;
  color: string;
  parentId?: string;
}
