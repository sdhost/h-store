package edu.brown.benchmark.auctionmark.util;

public class Category {
    private int categoryID;
    private int parentCategoryID;
    private int itemCount;
    private String name;
    private boolean isLeaf;

    public Category(int categoryID, String name, int parentCategoryID, int itemCount, boolean isLeaf) {
        this.categoryID = categoryID;
        this.name = name;
        this.parentCategoryID = parentCategoryID;
        this.itemCount = itemCount;
        this.isLeaf = isLeaf;
    }

    public String getName() {
        return this.name;
    }

    public int getCategoryID() {
        return this.categoryID;
    }

    public int getParentCategoryID() {
        return this.parentCategoryID;
    }

    public int getItemCount() {
        return this.itemCount;
    }

    public boolean isLeaf() {
        return this.isLeaf;
    }
}